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

package software.amazon.smithy.go.codegen.requestcompression;

import java.util.List;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.integration.ConfigField;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.go.codegen.integration.MiddlewareRegistrar;
import software.amazon.smithy.go.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ToShapeId;
import software.amazon.smithy.model.traits.RequestCompressionTrait;
import software.amazon.smithy.utils.ListUtils;

public final class RequestCompression implements GoIntegration {
    private static final String ADD_REQUEST_COMPRESSION = "addRequestCompression";

    private static final String ADD_REQUEST_COMPRESSION_INTERNAL = "AddRequestCompression";

    private static final String DISABLE_REQUEST_COMPRESSION = "DisableRequestCompression";

    private static final String REQUEST_MIN_COMPRESSION_SIZE_BYTES = "RequestMinCompressSizeBytes";

    @Override
    public void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            GoDelegator goDelegator
    ) {
        ServiceShape service = settings.getService(model);
        if (!isRequestCompressionService(model, service)) {
            return;
        }

        goDelegator.useShapeWriter(service, this::writeMiddlewareHelper);
    }


    public static boolean isRequestCompressionService(Model model, ServiceShape service) {
        TopDownIndex topDownIndex = TopDownIndex.of(model);
        for (ToShapeId operation : topDownIndex.getContainedOperations(service)) {
            OperationShape operationShape = model.expectShape(operation.toShapeId(), OperationShape.class);
            if (operationShape.hasTrait(RequestCompressionTrait.class)) {
                return true;
            }
        }
        return false;
    }

    private void writeMiddlewareHelper(GoWriter writer) {
        writer.openBlock("func $L(stack *middleware.Stack, options Options) error {", "}",
                ADD_REQUEST_COMPRESSION, () -> {
                    writer.write(
                    "return $T(stack, options.DisableRequestCompression, options.RequestMinCompressSizeBytes)",
                            SymbolUtils.createValueSymbolBuilder(ADD_REQUEST_COMPRESSION_INTERNAL,
                                    SmithyGoDependency.SMITHY_REQUEST_COMPRESSION).build()
                    );
                });
        writer.insertTrailingNewline();
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return ListUtils.of(
                RuntimeClientPlugin.builder()
                        .operationPredicate((model, service, operation) ->
                        operation.hasTrait(RequestCompressionTrait.class))
                        .registerMiddleware(MiddlewareRegistrar.builder()
                                .resolvedFunction(SymbolUtils.createValueSymbolBuilder(ADD_REQUEST_COMPRESSION).build())
                                .useClientOptions()
                                .build())
                        .build(),
                RuntimeClientPlugin.builder()
                        .servicePredicate(RequestCompression::isRequestCompressionService)
                        .configFields(ListUtils.of(
                                ConfigField.builder()
                                        .name(DISABLE_REQUEST_COMPRESSION)
                                        .type(SymbolUtils.createValueSymbolBuilder("bool")
                                                .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true)
                                                .build())
                                        .documentation("Determine if request compression is allowed, default to false")
                                        .build(),
                                ConfigField.builder()
                                        .name(REQUEST_MIN_COMPRESSION_SIZE_BYTES)
                                        .type(SymbolUtils.createValueSymbolBuilder("int64")
                                                .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true)
                                                .build())
                                        .documentation("Inclusive threshold request body size to trigger compression, "
                                         + "default to 10240 and must be within 0 and 10485760 bytes inclusively")
                                        .build()
                        ))
                        .build()
        );
    }
}
