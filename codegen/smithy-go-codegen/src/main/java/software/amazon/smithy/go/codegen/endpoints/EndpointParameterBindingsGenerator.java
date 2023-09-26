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
import java.util.Set;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.rulesengine.traits.ClientContextParamsTrait;
import software.amazon.smithy.rulesengine.traits.ContextParamTrait;
import software.amazon.smithy.rulesengine.traits.StaticContextParamDefinition;
import software.amazon.smithy.rulesengine.traits.StaticContextParamsTrait;
import software.amazon.smithy.utils.MapUtils;

/**
 * Generates endpoint parameter bindings.
 */
public class EndpointParameterBindingsGenerator {
    private final ProtocolGenerator.GenerationContext context;

    public EndpointParameterBindingsGenerator(ProtocolGenerator.GenerationContext context) {
        this.context = context;
    }

    public GoWriter.Writable generate() {
        return goTemplate("""
                $bindFunc:W

                $operationBindFuncs:W
                """,
                MapUtils.of(
                        "bindFunc", generateBindFunc(),
                        "operationBindFuncs", generateOperationBindings()
                ));
    }

    private GoWriter.Writable generateBindFunc() {
        return goTemplate("""
                type endpointParamsBinder interface {
                    bindEndpointParams(*EndpointParameters)
                }

                func bindEndpointParams(input endpointParamsBinder, options Options) *EndpointParameters {
                    params := &EndpointParameters{}

                    $builtinBindings:W

                    $clientContextBindings:W

                    input.bindEndpointParams(params)

                    return params
                }
                """,
                MapUtils.of(
                        "builtinBindings", generateBuiltinBindings(),
                        "clientContextBindings", generateClientContextParamBindings()
                ));
    }

    private GoWriter.Writable generateOperationBindings() {
        Set<OperationShape> operations =
                TopDownIndex.of(context.getModel()).getContainedOperations(context.getService());

        return writer -> {
            for (OperationShape operation : operations) {
                StructureShape input = context.getModel().expectShape(operation.getInput().get(), StructureShape.class);
                writer.write("""
                        func (in $P) bindEndpointParams(p *EndpointParameters) {
                            $W

                            $W
                        }
                        """,
                        context.getSymbolProvider().toSymbol(input),
                        generateContextParamBindings(input),
                        generateStaticContextParamBindings(operation));
            }
        };
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

    private GoWriter.Writable generateClientContextParamBindings() {
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

    private GoWriter.Writable generateContextParamBindings(StructureShape input) {
        return writer -> {
            input.getAllMembers().values().forEach(inputMember -> {
                var contextParamTraitOpt = inputMember.getTrait(ContextParamTrait.class);
                if (contextParamTraitOpt.isPresent()) {
                    var contextParamTrait = contextParamTraitOpt.get();
                    writer.write(
                            """
                            p.$L = in.$L
                            """,
                            contextParamTrait.getName(),
                            inputMember.getMemberName()
                    );
                }
            });
            writer.write("");
        };
    }

    private GoWriter.Writable generateStaticContextParamBindings(OperationShape operation) {
        if (!operation.hasTrait(StaticContextParamsTrait.class)) {
            return goTemplate("");
        }

        StaticContextParamsTrait params = operation.expectTrait(StaticContextParamsTrait.class);
        return writer -> {
            params.getParameters().forEach((k, v) -> {
                writer.write("p.$L = $W", k, generateStaticLiteral(v));
            });
        };
    }

    private GoWriter.Writable generateStaticLiteral(StaticContextParamDefinition literal) {
        return writer -> {
            Node value = literal.getValue();
            if (value.isStringNode()) {
                writer.writeInline("$T($S)",
                        SymbolUtils.createValueSymbolBuilder("String", SmithyGoDependency.SMITHY_PTR).build(),
                        value.expectStringNode().getValue());
            } else if (value.isBooleanNode()) {
                writer.writeInline("$T($L)",
                        SymbolUtils.createValueSymbolBuilder("Bool", SmithyGoDependency.SMITHY_PTR).build(),
                        value.expectBooleanNode().getValue());
            } else {
                throw new CodegenException("unrecognized static context param value type");
            }
        };
    }
}
