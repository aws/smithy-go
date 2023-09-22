/*
 * Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.go.codegen.auth;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.ArrayList;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.utils.MapUtils;

/**
 * Generates a single method which binds auth scheme resolver parameters from operation input and client options.
 * The only value bound by default is the operation name. Generators must load additional bindings through
 * GoIntegration.
 */
public class AuthParametersResolverGenerator {
    public static final String FUNC_NAME = "bindAuthResolverParams";

    public static final Symbol FUNC_SYMBOL = SymbolUtils.createValueSymbolBuilder(FUNC_NAME).build();

    private final ProtocolGenerator.GenerationContext context;

    private final ArrayList<AuthParametersResolver> resolvers = new ArrayList<>();

    public AuthParametersResolverGenerator(ProtocolGenerator.GenerationContext context) {
        this.context = context;
    }

    public GoWriter.Writable generate() {
        loadBindings();

        var paramsSymbol = SymbolUtils.createPointableSymbolBuilder(AuthParametersGenerator.STRUCT_NAME).build();

        return goTemplate("""
                $operationNamer:W

                func $name:L(input interface{}, options Options) $params:P {
                    params := &$params:T{
                        Operation: input.(operationNamer).operationName(),
                    }

                    $bindings:W

                    return params
                }
                """,
                MapUtils.of(
                        "name", FUNC_NAME,
                        "operationNamer", generateOperationNamer(),
                        "params", paramsSymbol,
                        "bindings", generateBindings()
                ));
    }

    private GoWriter.Writable generateOperationNamer() {
        return (writer) -> {
            writer.write("""
                    type operationNamer interface {
                        operationName() string
                    }
                    """);
        };
    }

    private GoWriter.Writable generateBindings() {
        return (writer) -> {
            for (var resolver: resolvers) {
                writer.write("$T(params, input, options)", resolver.resolver());
            }
        };
    }

    private void loadBindings() {
        for (var integration: context.getIntegrations()) {
            var plugins = integration.getClientPlugins().stream().filter(it ->
                    it.matchesService(context.getModel(), context.getService())).toList();
            for (var plugin: plugins) {
                resolvers.addAll(plugin.getAuthParameterResolvers());
            }
        }
    }
}
