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

import java.util.List;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.utils.ListUtils;

/**
 * Adds a client logger to the client.
 */
public class ClientLogger implements GoIntegration {
    private static final String DEFAULT_LOGGER_RESOLVER = "resolveDefaultLogger";
    private static final String LOGGER_CONFIG_NAME = "Logger";
    private static final String SET_LOGGER_CONTEXT_RESOLVER = "setLoggerContext";

    @Override
    public byte getOrder() {
        return -127;
    }

    @Override
    public void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            GoDelegator goDelegator
    ) {
        goDelegator.useShapeWriter(settings.getService(model), writer -> {
            writer.openBlock("func $L(o *Options) {", "}", DEFAULT_LOGGER_RESOLVER, () -> {
                Symbol nopSymbol = SymbolUtils.createValueSymbolBuilder("Nop", SmithyGoDependency.SMITHY_LOGGING)
                        .build();
                writer.openBlock("if o.$L != nil {", "}", LOGGER_CONFIG_NAME, () -> {
                    writer.write("return");
                });
                writer.write("o.$L = $T{}", LOGGER_CONFIG_NAME, nopSymbol);
            });
            writer.write("");

            // Sets the logger on the request context in invokeOperation.
            Symbol contextSymbol = SymbolUtils.createValueSymbolBuilder("Context", SmithyGoDependency.CONTEXT).build();
            Symbol setLogger = SymbolUtils.createValueSymbolBuilder("SetLogger", SmithyGoDependency.SMITHY_MIDDLEWARE)
                    .build();
            writer.openBlock("func $L(ctx $T, options Options, operation string) $T {", "}",
                    SET_LOGGER_CONTEXT_RESOLVER, contextSymbol, contextSymbol, () -> {
                        writer.write("_ = operation");
                        writer.write("return $T(ctx, options.$L)", setLogger, LOGGER_CONFIG_NAME);
                    });
            writer.write("");
        });
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return ListUtils.of(
                RuntimeClientPlugin.builder()
                        .addConfigField(ConfigField.builder()
                                .name(LOGGER_CONFIG_NAME)
                                .type(SymbolUtils.createValueSymbolBuilder("Logger", SmithyGoDependency.SMITHY_LOGGING)
                                        .build())
                                .documentation("The logger writer interface to write logging messages to.")
                                .build())
                        .addConfigFieldResolver(ConfigFieldResolver.builder()
                                .location(ConfigFieldResolver.Location.CLIENT)
                                .target(ConfigFieldResolver.Target.INITIALIZATION)
                                .resolver(SymbolUtils.createValueSymbolBuilder(DEFAULT_LOGGER_RESOLVER).build())
                                .build())
                        .addOperationContextResolver(
                                SymbolUtils.createValueSymbolBuilder(SET_LOGGER_CONTEXT_RESOLVER).build())
                        .isCommon(true)
                        .build()
        );
    }
}
