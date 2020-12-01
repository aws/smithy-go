/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.go.codegen.integration;

import java.util.Map;
import java.util.Optional;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.utils.StringUtils;
import software.amazon.smithy.waiters.Acceptor;
import software.amazon.smithy.waiters.Matcher;
import software.amazon.smithy.waiters.PathComparator;
import software.amazon.smithy.waiters.WaitableTrait;
import software.amazon.smithy.waiters.Waiter;

/**
 * Implements support for WaitableTrait.
 */
public class Waiters implements GoIntegration {
    private static final String WAITER_INVOKER_FUNCTION_NAME = "Wait";
    private static final String WAITER_MIDDLEWARE_NAME = "WaiterRetrier";
    private static final int DEFAULT_MAX_WAIT_TIME = 300;
    private static final int DEFAULT_MAX_ATTEMPTS = 8;


    @Override
    public void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            GoDelegator goDelegator
    ) {
        ServiceShape serviceShape = settings.getService(model);
        TopDownIndex topDownIndex = TopDownIndex.of(model);

        topDownIndex.getContainedOperations(serviceShape).stream()
                .forEach(operation -> {
                    if (!operation.hasTrait(WaitableTrait.ID)) {
                        return;
                    }

                    Map<String, Waiter> waiters = operation.expectTrait(WaitableTrait.class).getWaiters();

                    goDelegator.useShapeWriter(operation, writer -> {
                        generateOperationWaiter(model, symbolProvider, writer, operation, waiters);
                    });
                });
    }


    /**
     * Generates all waiter components used for the operation.
     */
    private void generateOperationWaiter(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            OperationShape operation,
            Map<String, Waiter> waiters
    ) {
        // write client interface
        generateApiClientInterface(model, symbolProvider, writer, operation);

        // generate waiter function
        waiters.forEach((name, waiter) -> {
            // write waiter options
            generateWaiterOptions(model, symbolProvider, writer, operation, name);

            // write waiter client
            generateWaiterClient(model, symbolProvider, writer, operation, name, waiter);

            // write waiter specific invoker
            generateWaiterInvoker(model, symbolProvider, writer, operation, name, waiter);

            // write waiter state mutator for each waiter
            generateWaiterStateMutator(model, symbolProvider, writer, operation, name, waiter);
        });
    }

    /**
     * Generates interface to satisfy service client and invoke the relevant operation.
     */
    private void generateApiClientInterface(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            OperationShape operationShape
    ) {
        StructureShape inputShape = model.expectShape(
                operationShape.getInput().get(), StructureShape.class
        );
        StructureShape outputShape = model.expectShape(
                operationShape.getOutput().get(), StructureShape.class
        );

        Symbol operationSymbol = symbolProvider.toSymbol(operationShape);
        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);
        Symbol outputSymbol = symbolProvider.toSymbol(outputShape);

        String interfaceName = generateApiClientInterfaceName(operationSymbol);

        writer.write("");
        writer.writeDocs(
                String.format("%s is a client that implements %s operation", interfaceName, operationSymbol.getName())
        );
        writer.openBlock("type $L interface {", "}",
                interfaceName, () -> {
                    writer.addUseImports(SmithyGoDependency.CONTEXT);
                    writer.write(
                            "$T(context.Context, $P, ...func(*Options)) ($P, error)",
                            operationSymbol, inputSymbol, outputSymbol
                    );
                }
        );
        writer.write("");
    }

    /**
     * Generates waiter options to configure a waiter client.
     */
    private void generateWaiterOptions(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            OperationShape operationShape,
            String waiterName
    ) {
        String optionsName = generateWaiterOptionsName(waiterName);
        String waiterClientName = generateWaiterClientName(waiterName);

        writer.write("");
        writer.writeDocs(
                String.format("%s are waiter options for %s", optionsName, waiterClientName)
        );

        writer.openBlock("type $L struct {", "}",
                optionsName, () -> {
                    writer.addUseImports(SmithyGoDependency.TIME);

                    writer.write("");
                    writer.writeDocs("MinDelay is the minimum amount of time to delay between retries in seconds");
                    writer.write("MinDelay time.Duration");

                    writer.write("");
                    writer.writeDocs("MaxDelay is the maximum amount of time to delay between retries in seconds");
                    writer.write("MaxDelay time.Duration");

                    writer.write("");
                    writer.writeDocs("MaxWaitTime is the maximum amount of wait time in seconds before a waiter is "
                            + "forced to return");
                    writer.write("MaxWaitTime time.Duration");

                    writer.write("");
                    writer.writeDocs("MaxAttempts is the maximum number of attempts to fetch a terminal waiter state");
                    writer.write("MaxAttempts int64");

                    writer.write("");
                    writer.writeDocs("EnableLogger is used to enable logging for waiter retry attempts");
                    writer.write("EnableLogger bool");

                    writer.write("");
                    writer.writeDocs(
                            "WaiterStateMutator is mutator function option that can be used to override the "
                                    + "service defined waiter-behavior based on operation output, or returned error. "
                                    + "The mutator function is used by the waiter to decide if a state is retryable "
                                    + "or a terminal state.\n\nBy default service-modeled waiter state mutators "
                                    + "will populate this option. This option can thus be used to define a custom "
                                    + "waiter state with fall-back to service-modeled waiter state mutators."
                    );
                    writer.write(
                            "WaiterStateMutator func(context.Context, interface{}, interface{}, error) "
                                    + "(bool, error)");
                }
        );
        writer.write("");
    }


    /**
     * Generates waiter client used to invoke waiter function. The waiter client is specific to a modeled waiter.
     * Each waiter client is unique within a enclosure of a service.
     * This function also generates a waiter client constructor that takes in a API client interface, and waiter options
     * to configure a waiter client.
     */
    private void generateWaiterClient(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            OperationShape operationShape,
            String waiterName,
            Waiter waiter
    ) {
        Symbol operationSymbol = symbolProvider.toSymbol(operationShape);
        String clientName = generateWaiterClientName(waiterName);

        writer.write("");
        writer.writeDocs(
                String.format("%s defines the waiters for %s", clientName, waiterName)
        );
        writer.openBlock("type $L struct {", "}",
                clientName, () -> {
                    writer.write("");
                    writer.write("client $L", generateApiClientInterfaceName(operationSymbol));

                    writer.write("");
                    writer.write("options $L", generateWaiterOptionsName(waiterName));
                });

        writer.write("");

        String constructorName = String.format("New%s", clientName);

        Symbol waiterOptionsSymbol = SymbolUtils.createPointableSymbolBuilder(
                generateWaiterOptionsName(waiterName)
        ).build();

        Symbol clientSymbol = SymbolUtils.createPointableSymbolBuilder(
                clientName
        ).build();

        writer.writeDocs(
                String.format("%s constructs a %s.", constructorName, clientName)
        );
        writer.openBlock("func $L(client $L, optFns ...func($P)) $P {", "}",
                constructorName, generateApiClientInterfaceName(operationSymbol), waiterOptionsSymbol, clientSymbol,
                () -> {
                    writer.write("options := $T{}", waiterOptionsSymbol);
                    writer.addUseImports(SmithyGoDependency.TIME);

                    // set defaults
                    writer.write("options.MinDelay = $L * time.Second", waiter.getMinDelay());
                    writer.write("options.MaxDelay = $L * time.Second", waiter.getMaxDelay());
                    // set defaults not defined in model
                    writer.write("options.MaxWaitTime = $L * time.Second", DEFAULT_MAX_WAIT_TIME);
                    writer.write("options.MaxAttempts = $L ", DEFAULT_MAX_ATTEMPTS);
                    writer.write("options.WaiterStateMutator = $L", generateWaiterStateMutatorName(waiterName));
                    writer.write("");

                    writer.openBlock("for _, fn := range optFns {",
                            "}", () -> {
                                writer.write("fn(&options)");
                            });

                    writer.openBlock("return &$T {", "}", clientSymbol, () -> {
                        writer.write("client: client, ");
                        writer.write("options: options, ");
                    });
                });
    }

    /**
     * Generates waiter invoker functions to call specific operation waiters
     * These waiter invoker functions is defined on each modeled waiter client.
     * The invoker function takes in a context, along with operation input, and
     * optional functional options for the waiter.
     */
    private void generateWaiterInvoker(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            OperationShape operationShape,
            String waiterName,
            Waiter waiter
    ) {
        StructureShape inputShape = model.expectShape(
                operationShape.getInput().get(), StructureShape.class
        );

        Symbol operationSymbol = symbolProvider.toSymbol(operationShape);
        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);

        Symbol waiterOptionsSymbol = SymbolUtils.createPointableSymbolBuilder(
                generateWaiterOptionsName(waiterName)
        ).build();

        Symbol clientSymbol = SymbolUtils.createPointableSymbolBuilder(
                generateWaiterClientName(waiterName)
        ).build();

        Symbol waiterMiddlewareSymbol = SymbolUtils.createValueSymbolBuilder(
                WAITER_MIDDLEWARE_NAME, SmithyGoDependency.SMITHY_WAITERS
        ).build();

        writer.write("");
        writer.addUseImports(SmithyGoDependency.CONTEXT);
        writer.writeDocs(
                String.format("%s calls the waiter function for %s waiter", WAITER_INVOKER_FUNCTION_NAME, waiterName)
        );
        writer.openBlock("func (w $P) $L(ctx context.Context, params $P, optFns ...func($P)) error {", "}",
                clientSymbol, WAITER_INVOKER_FUNCTION_NAME, inputSymbol, waiterOptionsSymbol,
                () -> {
                    writer.write("options := $T{}", waiterOptionsSymbol);
                    writer.write("options.MinDelay = w.options.MinDelay");
                    writer.write("options.MaxDelay = w.options.MaxDelay");
                    writer.write("options.MaxWaitTime = w.options.MaxWaitTime");
                    writer.write("options.MaxAttempts = w.options.MaxAttempts");
                    writer.write("options.EnableLogger = w.options.EnableLogger");
                    writer.write("options.WaiterStateMutator = w.options.WaiterStateMutator");

                    writer.openBlock("for _, fn := range optFns {",
                            "}", () -> {
                                writer.write("fn(&options)");
                            });

                    writer.openBlock("_, err := w.client.$T(ctx, params, func (o *Options) { ", "})",
                            operationSymbol, () -> {
                                writer.openBlock("o.APIOptions = append(o.APIOptions, "
                                                + "func(stack *middleware.Stack) error {", "})",
                                        () -> {
                                            writer.openBlock("return stack.Finalize.Add(&$T{", "}, middleware.Before)",
                                                    waiterMiddlewareSymbol,
                                                    () -> {
                                                        writer.write("MinDelay : options.MinDelay, ");
                                                        writer.write("MaxDelay : options.MaxDelay, ");
                                                        writer.write("MaxWaitTime : options.MaxWaitTime, ");
                                                        writer.write("MaxAttempts : options.MaxAttempts, ");
                                                        writer.write("EnableLogger : options.EnableLogger, ");
                                                        writer.write(
                                                                "WaiterStateMutator : options.WaiterStateMutator, ");
                                                        writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_TRANSPORT);
                                                        writer.write("RequestCloner : smithyhttp.RequestCloner, ");
                                                    });

                                        });
                            });
                    writer.write("");

                    writer.addUseImports(SmithyGoDependency.FMT);
                    writer.write("if err != nil { return fmt.Errorf(\"$L errored with error %w\", err) }",
                            clientSymbol);
                    writer.write("return nil");
                });
    }

    /**
     * Generates a waiter state mutator function which is used by the WaiterRetrier Middleware to mutate
     * waiter state as per the defined logic and returned operation response.
     *
     * @param model          the smithy model
     * @param symbolProvider symbol provider
     * @param writer         the Gowriter
     * @param operationShape operation shape on which the waiter is modeled
     * @param waiterName     the waiter name
     * @param waiter         the waiter structure that contains info on modeled waiter
     */
    private void generateWaiterStateMutator(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            OperationShape operationShape,
            String waiterName,
            Waiter waiter
    ) {
        StructureShape inputShape = model.expectShape(
                operationShape.getInput().get(), StructureShape.class
        );
        StructureShape outputShape = model.expectShape(
                operationShape.getOutput().get(), StructureShape.class
        );

        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);
        Symbol outputSymbol = symbolProvider.toSymbol(outputShape);

        writer.openBlock("func $L(ctx context.Context, inputIface, outputIface interface{}, err error) (bool, error) {",
                "}", generateWaiterStateMutatorName(waiterName), () -> {

                    writer.write("input, ok := inputIface.($P)", inputSymbol);
                    writer.addUseImports(SmithyGoDependency.FMT);
                    writer.write("if !ok { return false, fmt.Errorf(\"expected input to be of type $P\")}",
                            inputSymbol
                    );
                    writer.write("_ = input").write("");

                    writer.write("output, ok := outputIface.($P)", outputSymbol);
                    writer.addUseImports(SmithyGoDependency.FMT);
                    writer.write("if !ok { return false, fmt.Errorf(\"expected output to be of type $P\")}",
                            outputSymbol
                    );
                    writer.write("_ = output").write("");


                    writer.openBlock("type wrapper struct {", "}", () -> {
                        writer.write("Input $P", inputSymbol);
                        writer.write("Output $P", outputSymbol);
                    });

                    writer.write("wrappedIO := &wrapper{ Input: input,\n Output: output,\n}");
                    writer.write("_ = wrappedIO");

                    waiter.getAcceptors().forEach(acceptor -> {
                        Matcher matcher = acceptor.getMatcher();
                        switch (matcher.getMemberName()) {
                            case "output":
                                writer.write("");
                                writer.addUseImports(SmithyGoDependency.GO_JMESPATH);
                                writer.addUseImports(SmithyGoDependency.FMT);

                                writer.openBlock("{", "}", () -> {
                                    writer.write("if err != nil { return true, nil }");

                                    Matcher.OutputMember outputMember = (Matcher.OutputMember) matcher;
                                    String path = outputMember.getValue().getPath();
                                    String expectedValue = outputMember.getValue().getExpected();
                                    PathComparator comparator = outputMember.getValue().getComparator();

                                    writer.write("pathValue, err :=  jmespath.Search($S, output)", path);
                                    writer.openBlock("if err != nil {", "}", () -> {
                                        writer.write(
                                                "return false, fmt.Errorf(\"error evaluating waiter state: %w\", err)");
                                    }).write("");
                                    writer.write("expectedValue := $S", expectedValue);
                                    writeWaiterComparator(writer, acceptor, comparator, "pathValue", "expectedValue");
                                });
                                break;

                            case "inputOutput":
                                writer.write("");
                                writer.addUseImports(SmithyGoDependency.GO_JMESPATH);
                                writer.addUseImports(SmithyGoDependency.FMT);

                                writer.openBlock("{", "}", () -> {
                                    writer.write("if err != nil { return true, nil }");

                                    Matcher.InputOutputMember ioMember = (Matcher.InputOutputMember) matcher;
                                    String path = ioMember.getValue().getPath();
                                    String expectedValue = ioMember.getValue().getExpected();
                                    PathComparator comparator = ioMember.getValue().getComparator();

                                    writer.write("pathValue, err :=  jmespath.Search($S, wrappedIO)", path);
                                    writer.openBlock("if err != nil {", "}", () -> {
                                        writer.write(
                                                "return false, fmt.Errorf(\"error evaluating waiter state: %w\", err)");
                                    });
                                    writer.write("");
                                    writer.write("expectedValue := $S", expectedValue);
                                    writeWaiterComparator(writer, acceptor, comparator, "pathValue", "expectedValue");
                                });
                                break;

                            case "success":
                                writer.write("");

                                Matcher.SuccessMember successMember = (Matcher.SuccessMember) matcher;
                                writer.openBlock("{", "}", () -> {
                                    writer.openBlock("if err != nil {", "}",
                                            () -> {
                                                writer.write("return true, nil");
                                            });

                                    writeMatchedAcceptorReturn(writer, acceptor);
                                });
                                break;

                            case "errorType":
                                writer.write("if err == nil { return true, nil }");
                                writer.write("");

                                writer.openBlock("{", "}", () -> {
                                    Matcher.ErrorTypeMember errorTypeMember = (Matcher.ErrorTypeMember) matcher;
                                    String errorType = errorTypeMember.getValue();

                                    // identify if this is a modeled error shape
                                    Optional<ShapeId> errorShape = operationShape.getErrors().stream().filter(
                                            shapeId -> {
                                                return shapeId.getName().equalsIgnoreCase(errorType);
                                            }).findFirst();

                                    // if modeled error shape
                                    if (errorShape.isPresent()) {
                                        Symbol modeledErrorSymbol = SymbolUtils.createValueSymbolBuilder(
                                                errorShape.get().getName(), "types"
                                        ).build();
                                        writer.addUseImports(SmithyGoDependency.ERRORS);
                                        writer.write("var errorType *$T", modeledErrorSymbol);
                                        writer.openBlock("if errors.As(err, &errorType) {", "}",
                                                () -> {
                                                    writeMatchedAcceptorReturn(writer, acceptor);
                                                });
                                    } else {
                                        // fall back to un-modeled error shape matching
                                        writer.addUseImports(SmithyGoDependency.STRINGS);
                                        writer.openBlock("if strings.Contains(err.Error(), $S) {",
                                                "}", errorType, () -> {
                                                    writeMatchedAcceptorReturn(writer, acceptor);
                                                });
                                    }

                                    writer.write("return true, nil");
                                });
                                writer.write("");
                                break;

                            default:
                                throw new CodegenException(
                                        String.format("unknown waiter state : %v", matcher.getMemberName())
                                );
                        }
                    });
                });
    }

    /**
     * writes comparators for a given waiter. The comparators are defined within the waiter acceptor.
     *
     * @param writer     the Gowriter
     * @param acceptor   the waiter acceptor that defines the comparator and acceptor states
     * @param comparator the comparator
     * @param actual     the variable carrying the actual value obtained.
     *                   This may be computed via a jmespath expression or operation response status (success/failure)
     * @param expected   the variable carrying the expected value. This value is as per the modeled waiter.
     */
    private void writeWaiterComparator(
            GoWriter writer,
            Acceptor acceptor,
            PathComparator comparator,
            String actual,
            String expected
    ) {
        switch (comparator) {
            case STRING_EQUALS:
                writer.openBlock("if $L == $L {", "}", actual, expected, () -> {
                    writeMatchedAcceptorReturn(writer, acceptor);
                });
                writer.write("return true, nil");
                break;

            case BOOLEAN_EQUALS:
                writer.addUseImports(SmithyGoDependency.STRCONV);
                writer.write("bv, err := strconv.ParseBool($L)", expected);
                writer.write(
                        "if err != nil { return false, fmt.Errorf(\"error parsing boolean from string %w\", err)}");
                writer.openBlock("if $L == bv {", "}", actual, () -> {
                    writeMatchedAcceptorReturn(writer, acceptor);
                });
                writer.write("return true, nil");
                break;

            case ALL_STRING_EQUALS:
                writer.openBlock("for _, v := range actual {", "}", () -> {
                    writer.write("if v != $L { return true, nil }", expected);
                });
                writer.write("");
                writeMatchedAcceptorReturn(writer, acceptor);
                break;

            case ANY_STRING_EQUALS:
                writer.openBlock("for _, v := range actual {", "}", () -> {
                    writer.openBlock("if v == $L {", "}",
                            expected, () -> {
                                writeMatchedAcceptorReturn(writer, acceptor);
                            });
                });
                writer.write("return true, nil");
                break;

            default:
                throw new CodegenException(
                        String.format("Found unknown waiter path comparator, %s", comparator.toString()));
        }

        writer.write("");
    }


    /**
     * Writes return statement for state where a waiter's acceptor state is a match.
     *
     * @param writer   the Go writer
     * @param acceptor the waiter acceptor who's state is used to write an appropriate return statement.
     */
    private void writeMatchedAcceptorReturn(GoWriter writer, Acceptor acceptor) {
        switch (acceptor.getState()) {
            case SUCCESS:
                writer.write("return false, nil");
                break;

            case FAILURE:
                writer.addUseImports(SmithyGoDependency.FMT);
                writer.write("return false, fmt.Errorf(\"waiter state transitioned to Failure\")");
                break;

            case RETRY:
                writer.write("return true, nil");
                break;

            default:
                throw new CodegenException("unknown acceptor state defined for the waiter");
        }
    }


    private String generateApiClientInterfaceName(
            Symbol operationSymbol
    ) {
        return String.format("%sWaiterAPIClient", operationSymbol.getName());
    }

    private String generateWaiterOptionsName(
            String waiterName
    ) {
        waiterName = StringUtils.capitalize(waiterName);
        return String.format("%sWaiterOptions", waiterName);
    }

    private String generateWaiterClientName(
            String waiterName
    ) {
        waiterName = StringUtils.capitalize(waiterName);
        return String.format("%sWaiter", waiterName);
    }

    private String generateWaiterStateMutatorName(
            String waiterName
    ) {
        waiterName = StringUtils.uncapitalize(waiterName);
        return String.format("%sStateMutator", waiterName);
    }

}
