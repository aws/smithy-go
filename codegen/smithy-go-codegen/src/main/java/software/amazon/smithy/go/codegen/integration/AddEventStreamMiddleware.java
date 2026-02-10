/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.MiddlewareIdentifier;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.EventStreamGenerator;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.EventStreamIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

/**
 * Adds Build middleware for operations that contain event stream v2 operations.
 */
public class AddEventStreamMiddleware implements GoIntegration {

    private static final MiddlewareIdentifier MIDDLEWARE_ID = MiddlewareIdentifier.string("EventStreamBuildMiddleware");

    final List<RuntimeClientPlugin> runtimeClientPlugins = new ArrayList<>();
    final List<OperationShape> eventStreamOperations = new ArrayList<>();

    @Override
    public byte getOrder() {
        // We'd like this to be one of the first Build middlewares
        return -127;
    }

    @Override
    public void processFinalizedModel(GoSettings settings, Model model) {
        ServiceShape service = settings.getService(model);
        eventStreamOperations.addAll(getOperationsWithV2EventStream(model, service));

        eventStreamOperations.forEach((operation) -> {
            String middlewareHelperName = getMiddlewareHelperName(operation);
            runtimeClientPlugins.add(RuntimeClientPlugin.builder()
                    .operationPredicate((m, s, o) -> o.equals(operation))
                    .registerMiddleware(MiddlewareRegistrar.builder()
                            .resolvedFunction(SymbolUtils.createValueSymbolBuilder(middlewareHelperName).build())
                            .build())
                    .build()
            );
        });
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return runtimeClientPlugins;
    }

    @Override
    public void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            GoDelegator delegator
    ) {
        eventStreamOperations.forEach((operation) -> {
            delegator.useShapeWriter(operation, (writer) -> {
                writeMiddleware(writer, model, symbolProvider, operation);

                String middlewareName = getMiddlewareName(operation);
                String middlewareHelperName = getMiddlewareHelperName(operation);
                writer.addUseImports(SmithyGoDependency.SMITHY_MIDDLEWARE);
                // TODO make this not use openBlock
                writer.openBlock("func $L(stack *middleware.Stack) error {", "}",
                        middlewareHelperName,
                        () -> {
                            writer.write(
                                    "return stack.Build.Add(&$L{}, middleware.Before)",
                                    middlewareName);
                        });

                // writeInitialReplyType(writer, model, symbolProvider, operation);
            });
        });
    }

    /**
     * Gets a list of the operations with v2 event streams (non-legacy event streams).
     *
     * @param model   Model used for generation.
     * @param service Service for getting list of operations.
     * @return list of operations with v2 event streams.
     */
    public static List<OperationShape> getOperationsWithV2EventStream(Model model, ServiceShape service) {
        List<OperationShape> operations = new ArrayList<>();
        TopDownIndex.of(model).getContainedOperations(service).stream().forEach((operation) -> {
            if (!operationHasEventStream(model, service, operation)) {
                return;
            }

            operations.add(operation);
        });
        return operations;
    }

    private static boolean operationHasEventStream(Model model, ServiceShape service, OperationShape operation) {
         var outputInfo = EventStreamIndex.of(model).getOutputInfo(operation);
        return EventStreamGenerator.hasEventStream(model, operation) && !EventStreamGenerator.isLegacyEventStreamGenerator(operation) && outputInfo.isPresent();
    }

    private static void writeMiddleware(
            GoWriter writer,
            Model model,
            SymbolProvider symbolProvider,
            OperationShape operation
    ) {
        GoStackStepMiddlewareGenerator middlewareGenerator =
                GoStackStepMiddlewareGenerator.createBuildStepMiddleware(
                        getMiddlewareName(operation),
                        MIDDLEWARE_ID
                );

        middlewareGenerator.writeMiddleware(writer, (generator, w) -> {
            writer.addUseImports(SmithyGoDependency.FMT);
            w.write(addMiddleware(operation.getId().getName()));
        });
    }

    private static Writable addMiddleware(String opName) {
        return goTemplate("""
				       out, metadata, err = next.HandleBuild(ctx, in)
				       response := $opName:LInitialReply{
				               ResultMetadata: metadata,
				       }
				       res, ok := out.Result.(*$opName:LOutput)
				       if !ok {
				               // we have this as error, but really no one will be listening to it
				               return out, metadata, fmt.Errorf("unexpected type of result. expected $opName:LOutput, got %T. Additionally %w", out.Result, err)
				       }
				       if err != nil {
				               // fail the event stream because the middleware failed
				               res.eventStream.err.SetError(err)
				               res.GetStream().Close()
				       }
				       res.initialReply <- response
                       return out, metadata, err
                """, Map.of(
                    "opName", opName
                ));
    }


    private static String getMiddlewareName(OperationShape operation) {
        return String.format("eventStreamBuild_op%sMiddleware", operation.getId().getName());
    }

    private static String getMiddlewareHelperName(OperationShape operation) {
        return String.format("addEventStreamBuild_op%sMiddleware", operation.getId().getName());
    }
}
