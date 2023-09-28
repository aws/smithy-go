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

package software.amazon.smithy.go.codegen.endpoints;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.HashMap;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.rulesengine.traits.ClientContextParamsTrait;
import software.amazon.smithy.utils.MapUtils;

/**
 * Generates the main endpoint parameter binding function. Operation-specific bindings are generated elsewhere
 * conditionally through EndpointParameterOperationBindingsGenerator.
 */
public class EndpointParameterBindingsGenerator {
    private final ProtocolGenerator.GenerationContext context;

    public EndpointParameterBindingsGenerator(ProtocolGenerator.GenerationContext context) {
        this.context = context;
    }

    public GoWriter.Writable generate() {
        return goTemplate("""
                type endpointParamsBinder interface {
                    bindEndpointParams(*EndpointParameters)
                }

                func bindEndpointParams(input interface{}, options Options) *EndpointParameters {
                    params := &EndpointParameters{}

                    $builtinBindings:W

                    $clientContextBindings:W

                    if b, ok := input.(endpointParamsBinder); ok {
                        b.bindEndpointParams(params)
                    }

                    return params
                }
                """,
                MapUtils.of(
                        "builtinBindings", generateBuiltinBindings(),
                        "clientContextBindings", generateClientContextBindings()
                ));
    }

    private GoWriter.Writable generateBuiltinBindings() {
        var bindings = new HashMap<String, GoWriter.Writable>();
        for (var integration: context.getIntegrations()) {
            var plugins = integration.getClientPlugins(context.getModel(), context.getService());
            for (var plugin: plugins) {
                bindings.putAll(plugin.getEndpointBuiltinBindings());
            }
        }

        var boundBuiltins = context.getEndpointRules().getParameters().toList().stream().filter(it ->
                it.isBuiltIn() && bindings.containsKey(it.getBuiltIn().get())).toList();
        return writer -> {
            for (var param: boundBuiltins) {
                writer.write(
                        "params.$L = $W",
                        EndpointParametersGenerator.getExportedParameterName(param),
                        bindings.get(param.getBuiltIn().get()));
            }
        };
    }

    private GoWriter.Writable generateClientContextBindings() {
        if (!context.getService().hasTrait(ClientContextParamsTrait.class)) {
            return goTemplate("");
        }

        var contextParams = context.getService().expectTrait(ClientContextParamsTrait.class).getParameters();
        var params = context.getEndpointRules().getParameters().toList().stream().filter(it ->
                contextParams.containsKey(it.getName().asString()) && !it.isBuiltIn()).toList();
        return writer -> {
            params.forEach(it -> {
                writer.write("params.$1L = options.$1L",
                        EndpointParametersGenerator.getExportedParameterName(it));
            });
        };
    }
}
