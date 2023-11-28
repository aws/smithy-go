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

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.ArrayList;
import java.util.List;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoCodegenPlugin;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.integration.ConfigField;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.go.codegen.integration.MiddlewareRegistrar;
import software.amazon.smithy.go.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.RequestCompressionTrait;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.MapUtils;


public final class RequestCompression implements GoIntegration {
    private static final String ADD_REQUEST_COMPRESSION_INTERNAL = "AddRequestCompression";

    private static final String DISABLE_REQUEST_COMPRESSION = "DisableRequestCompression";

    private static final String REQUEST_MIN_COMPRESSION_SIZE_BYTES = "RequestMinCompressSizeBytes";

    private final List<RuntimeClientPlugin> runtimeClientPlugins = new ArrayList<>();

    private static String getAddRequestCompressionMiddlewareFuncName(String operationName) {
        return String.format("addOperation%sRequestCompressionMiddleware", operationName);
    }

    // Write operation plugin for request compression middleware
    @Override
    public void processFinalizedModel(GoSettings settings, Model model) {
        ServiceShape service = settings.getService(model);
        TopDownIndex.of(model)
                .getContainedOperations(service).forEach(operation -> {
                    if (!operation.hasTrait(RequestCompressionTrait.class)) {
                        return;
                    }
                    SymbolProvider symbolProvider = GoCodegenPlugin.createSymbolProvider(model, settings);
                    String funcName = getAddRequestCompressionMiddlewareFuncName(
                            symbolProvider.toSymbol(operation).getName()
                    );
                    runtimeClientPlugins.add(RuntimeClientPlugin.builder().operationPredicate((m, s, o) -> {
                        if (!o.hasTrait(RequestCompressionTrait.class)) {
                            return false;
                        }
                        return o.equals(operation);
                    }).registerMiddleware(MiddlewareRegistrar.builder()
                        .resolvedFunction(SymbolUtils.createValueSymbolBuilder(funcName).build())
                        .useClientOptions().build())
                        .build());
                });
    }

    @Override
    public void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            GoDelegator goDelegator
    ) {
        ServiceShape service = settings.getService(model);
        for (ShapeId operationID : service.getAllOperations()) {
            OperationShape operation = model.expectShape(operationID, OperationShape.class);
            if (!operation.hasTrait(RequestCompressionTrait.class)) {
                continue;
            }
            goDelegator.useShapeWriter(operation, writeMiddlewareHelper(symbolProvider, operation));
        }
    }


    public static boolean isRequestCompressionService(Model model, ServiceShape service) {
        return TopDownIndex.of(model)
                .getContainedOperations(service).stream()
                .anyMatch(it -> it.hasTrait(RequestCompressionTrait.class));
    }

    private GoWriter.Writable writeMiddlewareHelper(SymbolProvider symbolProvider, OperationShape operation) {
        var addInternalSymbol = SymbolUtils
                .createValueSymbolBuilder(ADD_REQUEST_COMPRESSION_INTERNAL,
                SmithyGoDependency.SMITHY_REQUEST_COMPRESSION)
                .build();
        String operationName = symbolProvider.toSymbol(operation).getName();
        RequestCompressionTrait trait = operation.expectTrait(RequestCompressionTrait.class);

        // build encoding list symbol
        StringBuilder algorithmList = new StringBuilder("[]string{");
        for (String algo : trait.getEncodings()) {
            algorithmList.append(String.format("\"%s\", ", algo));
        }
        String algorithms = algorithmList.substring(0, algorithmList.length() - 2) + "}";

        return goTemplate("""
                func $add:L(stack $stack:P, options Options) error {
                    return $addInternal:T(stack, options.DisableRequestCompression, options.RequestMinCompressSizeBytes,
                    $algorithms:L)
                }
                """,
                MapUtils.of(
                "add", getAddRequestCompressionMiddlewareFuncName(operationName),
                "stack", SmithyGoTypes.Middleware.Stack,
                "addInternal", addInternalSymbol,
                "algorithms", algorithms
                ));
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
       runtimeClientPlugins.add(
                RuntimeClientPlugin.builder()
                        .servicePredicate(RequestCompression::isRequestCompressionService)
                        .configFields(ListUtils.of(
                                ConfigField.builder()
                                        .name(DISABLE_REQUEST_COMPRESSION)
                                        .type(SymbolUtils.createValueSymbolBuilder("bool")
                                                .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true)
                                                .build())
                                        .documentation(
                                        "Whether to disable automatic request compression for supported operations.")
                                        .build(),
                                ConfigField.builder()
                                        .name(REQUEST_MIN_COMPRESSION_SIZE_BYTES)
                                        .type(SymbolUtils.createValueSymbolBuilder("int64")
                                                .putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true)
                                                .build())
                                        .documentation("The minimum request body size, in bytes, at which compression "
                                        + "should occur. The default value is 10 KiB. Values must fall within "
                                        + "[0, 1MiB].")
                                        .build()
                        ))
                        .build()
       );

       return runtimeClientPlugins;
    }
}
