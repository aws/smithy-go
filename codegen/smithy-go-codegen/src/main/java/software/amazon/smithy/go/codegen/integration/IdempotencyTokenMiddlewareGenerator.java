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

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.TriConsumer;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.IdempotencyTokenTrait;
import software.amazon.smithy.utils.ListUtils;

public class IdempotencyTokenMiddlewareGenerator implements GoIntegration {
    private void execute(
            GoWriter writer,
            SymbolProvider symbolProvider,
            OperationShape operation,
            MemberShape idempotencyTokenMemberShape,
            Symbol inputSymbol
    ) {
        GoStackStepMiddlewareGenerator middlewareGenerator =
            GoStackStepMiddlewareGenerator.createInitializeStepMiddleware(
                    getIdempotencyTokenMiddlewareName(operation));

        String memberName = symbolProvider.toMemberName(idempotencyTokenMemberShape);
            middlewareGenerator.writeMiddleware(writer, (generator, middlewareWriter) -> {
            middlewareWriter.write("input, ok := in.Parameters.($P)", inputSymbol);
            middlewareWriter.write("if !ok { return out, metadata, "
                    + "fmt.Errorf(\"expected middleware input to be of type $P \")}", inputSymbol);
            middlewareWriter.addUseImports(SmithyGoDependency.FMT);

            middlewareWriter.openBlock("if input.$L == nil {", "}", memberName, () -> {
            writer.addUseImports(SmithyGoDependency.SMITHY_RAND);
            writer.addUseImports(SmithyGoDependency.CRYPTORAND);
            writer.write("uuid := smithyrand.NewUUID(randReader)");
            writer.write("v, err := uuid.GetUUID()");
            writer.write("if err != nil { return out, metadata, err}");
                middlewareWriter.write("input.$L = &v", memberName);
            });
            middlewareWriter.write("return next.$L(ctx, in)", middlewareGenerator.getHandleMethodName());
        });
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
            TriConsumer<String, String, Consumer<GoWriter>> writerFactory
    ) {
        ServiceShape serviceShape =  settings.getService(model);
        Map<ShapeId, MemberShape> map = getOperationsWithIdempotencyToken(model, serviceShape);
        if (map.size() == 0) {
            return;
        }

        writerFactory.accept("idempotencyTokenMiddleware.go", settings.getModuleName(), writer -> {
            writer.write("var randReader io.Reader = cryptorand.Reader");
            writer.addUseImports(SmithyGoDependency.IO);
            for (Map.Entry<ShapeId, MemberShape> entry : map.entrySet()) {
                ShapeId operationId = entry.getKey();
                OperationShape operation = model.expectShape(operationId).asOperationShape().get();
                Shape inputShape  = model.expectShape(operation.getInput().get());
                MemberShape memberShape = entry.getValue();
                execute(writer, symbolProvider, operation, memberShape, symbolProvider.toSymbol(inputShape));
            }
        });
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
     * Gets a map with key as OperationId and Member shape as value for member shapes of an operation
     * decorated with the Idempotency token trait .
     *
     * @param  model Model used for generation.
     * @param  service Service for which idempotency token map is retrieved.
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
     * @param model Model used for generation.
     * @param operation Operation shape consisting of member decorated with idempotency token trait.
     * @return member shape decorated with Idempotency token trait.
     */
    private MemberShape getMemberWithIdempotencyToken(Model model, OperationShape operation) {
        OperationIndex operationIndex = model.getKnowledge(OperationIndex.class);
        Shape inputShape = operationIndex.getInput(operation).get();
        for (MemberShape member: inputShape.members()) {
            if (member.hasTrait(IdempotencyTokenTrait.class)) {
                return member;
            }
        }
        return null;
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return ListUtils.of(
                RuntimeClientPlugin.builder()
                        .operationPredicate((model, service, operation) -> {
                            if (getMemberWithIdempotencyToken(model, operation) != null) {
                                return true;
                            }
                            return false;
                        })
                        .buildMiddlewareStack((writer, service, operation, protocolGenerator, stackOperand) -> {
                            writer.write("$L.Initialize.Add(&$L{},middleware.After)",
                                    stackOperand,
                                    getIdempotencyTokenMiddlewareName(operation));
                            writer.addUseImports(SmithyGoDependency.SMITHY_MIDDLEWARE);
                        })
                        .build()
        );
    }
}
