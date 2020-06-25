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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.IdempotencyTokenTrait;

public class IdempotencyTokenMiddlewareGenerator implements GoIntegration {
    List<RuntimeClientPlugin> runtimeClientPlugins = new ArrayList<>();

    private void execute(
            Model model,
            GoWriter writer,
            SymbolProvider symbolProvider,
            OperationShape operation,
            MemberShape idempotencyTokenMemberShape
    ) {
        GoStackStepMiddlewareGenerator middlewareGenerator =
                GoStackStepMiddlewareGenerator.createInitializeStepMiddleware(
                        getIdempotencyTokenMiddlewareName(operation));

        Shape inputShape = model.expectShape(operation.getInput().get());
        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);
        String memberName = symbolProvider.toMemberName(idempotencyTokenMemberShape);
        middlewareGenerator.writeMiddleware(writer, (generator, middlewareWriter) -> {
            // if token provider is nil, skip this middleware
            middlewareWriter.openBlock("if m.tokenProvider == nil {", "}", () -> {
                middlewareWriter.write("return next.$L(ctx, in)", middlewareGenerator.getHandleMethodName());
            });

            middlewareWriter.write("input, ok := in.Parameters.($P)", inputSymbol);
            middlewareWriter.write("if !ok { return out, metadata, "
                    + "fmt.Errorf(\"expected middleware input to be of type $P \")}", inputSymbol);
            middlewareWriter.addUseImports(SmithyGoDependency.FMT);

            middlewareWriter.openBlock("if input.$L == nil {", "}", memberName, () -> {
                middlewareWriter.write("t, err := m.tokenProvider.GetToken()");
                middlewareWriter.write(" if err != nil { return out, metadata, err }");
                middlewareWriter.write("input.$L = &t", memberName);
            });
            middlewareWriter.write("return next.$L(ctx, in)", middlewareGenerator.getHandleMethodName());
        }, ((generator, memberWriter) -> {
            memberWriter.write("tokenProvider IdempotencyTokenProvider");
        }));

        writer.write("");
    }

    @Override
    public void processFinalizedModel(GoSettings settings, Model model) {
        ServiceShape service = settings.getService(model);
        for (ShapeId operationId : service.getAllOperations()) {
            OperationShape operation = model.expectShape(operationId, OperationShape.class);
            if (getMemberWithIdempotencyToken(model, operation) == null) {
                continue;
            }

            String getMiddlewareHelperName = getIdempotencyTokenMiddlewareHelperName(operation);
            RuntimeClientPlugin runtimeClientPlugin = RuntimeClientPlugin.builder()
                    .operationPredicate((predicatetModel, predicateService, predicateOperation) -> {
                        if (operation.equals(predicateOperation)) {
                            return true;
                        }
                        return false;
                    })
                    .registerMiddleware(MiddlewareRegistrar.builder()
                            .resolvedFunction(SymbolUtils.createValueSymbolBuilder(getMiddlewareHelperName).build())
                            .build())
                    .build();
            runtimeClientPlugins.add(runtimeClientPlugin);
        }
    }

    /**
     * Gets the sort order of the customization from -128 to 127, with lowest
     * executed first.
     *
     * @return Returns the sort order, defaults to 10.
     */
    @Override
    public byte getOrder() {
        return 10;
    }

    @Override
    public void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            GoDelegator delegator
    ) {
        ServiceShape serviceShape = settings.getService(model);
        Map<ShapeId, MemberShape> map = getOperationsWithIdempotencyToken(model, serviceShape);
        if (map.size() == 0) {
            return;
        }

        delegator.useShapeWriter(serviceShape, (writer) -> {
            writer.write("// IdempotencyTokenProvider interface for providing idempotency token");
            writer.openBlock("type IdempotencyTokenProvider interface {",
                    "}", () -> {
                        writer.write("GetToken() (string, error)");
                    });
            writer.write("");
        });

        for (Map.Entry<ShapeId, MemberShape> entry : map.entrySet()) {
            ShapeId operationShapeId = entry.getKey();
            OperationShape operation = model.expectShape(operationShapeId, OperationShape.class);
            delegator.useShapeWriter(operation, (writer) -> {
                        // Generate idempotency token middleware
                        MemberShape memberShape = map.get(operationShapeId);
                        execute(model, writer, symbolProvider, operation, memberShape);

                        // Generate idempotency token middleware registrar function
                        writer.addUseImports(SmithyGoDependency.SMITHY_MIDDLEWARE);
                        String middlewareHelperName = getIdempotencyTokenMiddlewareHelperName(operation);
                        writer.openBlock("func $L("
                                        + "stack *middleware.Stack, cfg IdempotencyTokenProvider) {",
                                "}", middlewareHelperName, () -> {
                                    writer.write("stack.Initialize.Add(&$L{cfg},middleware.After)",
                                            getIdempotencyTokenMiddlewareName(operation));
                                });
                    });
        }
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return runtimeClientPlugins;
    }

    /**
     * Get Idempotency Token Middleware name.
     *
     * @param operationShape Operation shape for which middleware is defined.
     * @return name of the idempotency token middleware.
     */
    private String getIdempotencyTokenMiddlewareName(OperationShape operationShape) {
        return String.format("idempotencyToken_initializeOp%s", operationShape.getId().getName());
    }

    /**
     * Get Idempotency Token Middleware Helper name.
     *
     * @param operationShape Operation shape for which middleware is defined.
     * @return name of the idempotency token middleware.
     */
    private String getIdempotencyTokenMiddlewareHelperName(OperationShape operationShape) {
        return String.format("addIdempotencyToken_op%sMiddleware", operationShape.getId().getName());
    }

    /**
     * Gets a map with key as OperationId and Member shape as value for member shapes of an operation
     * decorated with the Idempotency token trait.
     *
     * @param model   Model used for generation.
     * @param service Service for which idempotency token map is retrieved.
     * @return map of operation shapeId as key, member shape as value.
     */
    private Map<ShapeId, MemberShape> getOperationsWithIdempotencyToken(Model model, ServiceShape service) {
        Map<ShapeId, MemberShape> map = new TreeMap<>();
        service.getAllOperations().stream().forEach((operation) -> {
            OperationShape operationShape = model.expectShape(operation).asOperationShape().get();
            MemberShape memberShape = getMemberWithIdempotencyToken(model, operationShape);
            if (memberShape != null) {
                map.put(operation, memberShape);
            }
        });
        return map;
    }

    /**
     * Returns member shape which gets members decorated with Idempotency Token trait.
     *
     * @param model     Model used for generation.
     * @param operation Operation shape consisting of member decorated with idempotency token trait.
     * @return member shape decorated with Idempotency token trait.
     */
    private MemberShape getMemberWithIdempotencyToken(Model model, OperationShape operation) {
        OperationIndex operationIndex = model.getKnowledge(OperationIndex.class);
        Shape inputShape = operationIndex.getInput(operation).get();
        for (MemberShape member : inputShape.members()) {
            if (member.hasTrait(IdempotencyTokenTrait.class)) {
                return member;
            }
        }
        return null;
    }
}
