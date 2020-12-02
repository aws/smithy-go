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
import software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.MiddlewareIdentifier;
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
    private static final String WAITER_MIDDLEWARE_ID = "WaiterRetrier";
    private static final int DEFAULT_MAX_WAIT_TIME_IN_SECONDS = 300;
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

            // write waiter middleware for each waiter
            generateWaiterMiddleware(model, symbolProvider, writer, operation, name, waiter);
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

        StructureShape inputShape = model.expectShape(
                operationShape.getInput().get(), StructureShape.class
        );
        StructureShape outputShape = model.expectShape(
                operationShape.getOutput().get(), StructureShape.class
        );

        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);
        Symbol outputSymbol = symbolProvider.toSymbol(outputShape);

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
                            "WaiterStateMutator func(context.Context, $P, $P, error) "
                                    + "(bool, error)", inputSymbol, outputSymbol);
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
                    writer.write("options.MaxWaitTime = $L * time.Second", DEFAULT_MAX_WAIT_TIME_IN_SECONDS);
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
                                            writer.openBlock("return stack.Finalize.Add(&$L{", "}, middleware.Before)",
                                                    generateWaiterMiddlewareName(waiterName),
                                                    () -> {
                                                        writer.write("minDelay : options.MinDelay, ");
                                                        writer.write("maxDelay : options.MaxDelay, ");
                                                        writer.write("maxWaitTime : options.MaxWaitTime, ");
                                                        writer.write("maxAttempts : options.MaxAttempts, ");
                                                        writer.write("enableLogger : options.EnableLogger, ");
                                                        writer.write(
                                                                "waiterStateMutator : options.WaiterStateMutator, ");
                                                        writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_TRANSPORT);
                                                        writer.write("requestCloner : smithyhttp.RequestCloner, ");
                                                        writer.write("params: params, ");
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
     * Generates a waiter retrier middleware to trigger waiter state validation and retry behavior.
     *
     * @param model          the smithy model
     * @param symbolProvider symbol provider
     * @param writer         the GoWriter
     * @param operationShape operation shape on which waiter is modeled
     * @param waiterName     the waiter name
     * @param waiter         the waiter structure that contains info on modeled waiter
     */
    private void generateWaiterMiddleware(
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

        GoStackStepMiddlewareGenerator middlewareGenerator =
                GoStackStepMiddlewareGenerator.createFinalizeStepMiddleware(
                        generateWaiterMiddlewareName(waiterName),
                        MiddlewareIdentifier.builder()
                                .name(WAITER_MIDDLEWARE_ID)
                                .build()
                );

        writer.write("");

        middlewareGenerator.writeMiddleware(writer, (generator, w) -> {
            w.write("");
            w.addUseImports(SmithyGoDependency.SMITHY_LOGGING);
            w.writeDocs("fetch logger from context");
            w.write("logger := middleware.GetLogger(ctx)");
            w.write("");

            w.writeDocs("current attempt, delay");
            w.write("var attempt int64");
            w.write("var delay time.Duration");
            w.write("var remainingTime = m.maxWaitTime");

            // start loop
            w.write("for {");

            w.writeDocs("check number of attempts");
            w.write("if m.maxAttempts == attempt { break }");
            w.write("");
            w.write("attempt++");
            w.write("");

            w.write("attemptInput := in");
            w.write("attemptInput.Request = m.requestCloner(attemptInput.Request)");

            w.write("");

            // start retry only behavior
            w.write("if attempt > 1 {");

            Symbol computeDelaySymbol = SymbolUtils.createValueSymbolBuilder(
                    "ComputeDelay", SmithyGoDependency.SMITHY_WAITERS
            ).build();
            w.writeDocs("compute exponential backoff between waiter retries");
            w.write("delay = $T(m.minDelay, m.maxDelay, remainingTime, attempt)", computeDelaySymbol);
            w.write("");

            w.writeDocs("update the remaining time");
            w.write("remainingTime = remainingTime - delay");
            w.write("");

            w.writeDocs("rewind transport stream");
            w.write("if rewindable, ok := in.Request.(interface{ RewindStream() error }); ok {");
            w.write("if err := rewindable.RewindStream(); err != nil {");
            w.addUseImports(SmithyGoDependency.FMT);
            w.write("return out, metadata, fmt.Errorf(\"failed to rewind transport stream for retry, %w\", err)\n}");
            w.write("}");
            w.write("");

            Symbol sleepWithContextSymbol = SymbolUtils.createValueSymbolBuilder(
                    "SleepWithContext", SmithyGoDependency.SMITHY_WAITERS
            ).build();
            w.writeDocs("sleep for the delay amount before invoking a request");
            w.openBlock("if err = $T(ctx, delay); err != nil {", "}",
                    sleepWithContextSymbol, () -> {
                        w.write("return out, metadata, fmt.Errorf(\"request cancelled while waiting, %w\", err)");
                    });

            // enable logger
            w.writeDocs("log attempt");
            w.openBlock("if m.enableLogger {", "}", () -> {
                w.write("logger.Logf(logging.Debug, "
                        + "fmt.Sprintf(\"retrying waiter request, attempt count: %d\", attempt))\n");
            });
            w.write("");
            // end retry only behavior
            w.write("}");

            w.write("");
            w.writeDocs("attempt request");
            w.write("out, metadata, err = next.HandleFinalize(ctx, attemptInput)");
            w.write("");

            w.writeDocs("check for state mutation");
            w.write("output := out.Result.($P)", outputSymbol);
            w.write("retryable, err := m.waiterStateMutator(ctx, m.params, output, err)");
            w.write("");

            w.openBlock("if !retryable || err != nil {", "}", () -> {
                w.write("if m.enableLogger {");
                w.openBlock("if err != nil {", "} else {", () -> {
                    w.write("logger.Logf(logging.Debug, "
                            + "\"waiter transitioned to a failed state with unretryable error %v\", err)");
                });
                w.write("logger.Logf(logging.Debug, \"waiter transitioned to a success state\")}");
                w.write("}");
                w.write("return out, metadata, err");
            });
            // end loop
            w.write("}");

            w.openBlock("if m.enableLogger {", "}", () -> {
                w.write("logger.Logf(logging.Debug, \"max retry attempts exhausted, max %d\", attempt)");
            });

            w.addUseImports(SmithyGoDependency.FMT);
            w.write("return out, metadata, "
                    + "fmt.Errorf(\"exhausted all retry attempts while waiting for the resource state\")");

            w.write("");
        }, (generator, w) -> {
            w.write("");
            w.addUseImports(SmithyGoDependency.TIME);
            w.write("minDelay time.Duration ");
            w.write("maxDelay time.Duration ");
            w.write("maxWaitTime time.Duration");
            w.write("maxAttempts int64 ");
            w.write("enableLogger bool ");

            w.addUseImports(SmithyGoDependency.CONTEXT);
            w.write(
                    "waiterStateMutator func(context.Context, $P, $P, error) (bool, error)",
                    inputSymbol, outputSymbol
            );

            w.addUseImports(SmithyGoDependency.SMITHY_HTTP_TRANSPORT);
            w.write("requestCloner func(interface{}) interface{}");

            w.write("params $P", inputSymbol);
            w.write("");
        });
    }

    /**
     * Generates a waiter state mutator function which is used by the waiter retrier Middleware to mutate
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

        writer.openBlock("func $L(ctx context.Context, input $P, output $P, err error) (bool, error) {",
                "}", generateWaiterStateMutatorName(waiterName), inputSymbol, outputSymbol, () -> {
                    waiter.getAcceptors().forEach(acceptor -> {
                        // scope each acceptor to avoid name collisions
                        writer.openBlock("{", "}", () -> {
                            Matcher matcher = acceptor.getMatcher();
                            switch (matcher.getMemberName()) {
                                case "output":
                                    writer.write("");
                                    writer.addUseImports(SmithyGoDependency.GO_JMESPATH);
                                    writer.addUseImports(SmithyGoDependency.FMT);

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
                                    break;

                                case "inputOutput":
                                    writer.write("");
                                    writer.addUseImports(SmithyGoDependency.GO_JMESPATH);
                                    writer.addUseImports(SmithyGoDependency.FMT);

                                    writer.write("if err != nil { return true, nil }");

                                    Matcher.InputOutputMember ioMember = (Matcher.InputOutputMember) matcher;
                                    path = ioMember.getValue().getPath();
                                    expectedValue = ioMember.getValue().getExpected();
                                    comparator = ioMember.getValue().getComparator();

                                    writer.openBlock("pathValue, err :=  jmespath.Search($S, &struct{",
                                            "})", path, () -> {
                                                writer.write("Input $P \n Output $P \n }{", inputSymbol,
                                                        outputSymbol);
                                                writer.write("Input: input, \n Output: output, \n");
                                            });
                                    writer.openBlock("if err != nil {", "}", () -> {
                                        writer.write(
                                                "return false, fmt.Errorf(\"error evaluating waiter state: %w\", err)");
                                    });
                                    writer.write("");
                                    writer.write("expectedValue := $S", expectedValue);
                                    writeWaiterComparator(writer, acceptor, comparator, "pathValue", "expectedValue");
                                    break;

                                case "success":
                                    writer.write("");

                                    Matcher.SuccessMember successMember = (Matcher.SuccessMember) matcher;
                                    writer.openBlock("if err != nil {", "}",
                                            () -> {
                                                writer.write("return true, nil");
                                            });

                                    writeMatchedAcceptorReturn(writer, acceptor);
                                    break;

                                case "errorType":
                                    writer.write("if err == nil { return true, nil }");
                                    writer.write("");

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
                                    writer.write("");
                                    break;

                                default:
                                    throw new CodegenException(
                                            String.format("unknown waiter state : %v", matcher.getMemberName())
                                    );
                            }
                        });
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

    private String generateWaiterMiddlewareName(
            String waiterName
    ) {
        waiterName = StringUtils.uncapitalize(waiterName);
        return String.format("%sWaiterRetrier", waiterName);
    }

}
