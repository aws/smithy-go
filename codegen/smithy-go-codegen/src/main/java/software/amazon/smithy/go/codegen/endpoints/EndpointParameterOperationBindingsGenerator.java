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

import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.rulesengine.traits.ContextParamTrait;
import software.amazon.smithy.rulesengine.traits.StaticContextParamDefinition;
import software.amazon.smithy.rulesengine.traits.StaticContextParamsTrait;

/**
 * Generates operation-specific bindings (@contextParam + @staticContextParam) as a receiver method on the operation's
 * input structure.
 */
public class EndpointParameterOperationBindingsGenerator {
    private final OperationShape operation;
    private final StructureShape input;
    private final Symbol inputSymbol;

    public EndpointParameterOperationBindingsGenerator(
            OperationShape operation,
            StructureShape input,
            Symbol inputSymbol
    ) {
        this.operation = operation;
        this.input = input;
        this.inputSymbol = inputSymbol;
    }

    private boolean hasBindings() {
        var hasContextBindings = input.getAllMembers().values().stream().anyMatch(it ->
                it.hasTrait(ContextParamTrait.class));
        return hasContextBindings || operation.hasTrait(StaticContextParamsTrait.class);
    }

    public GoWriter.Writable generate() {
        if (!hasBindings()) {
            return goTemplate("");
        }

        return goTemplate("""
                func (in $P) bindEndpointParams(p *EndpointParameters) {
                    $W
                    $W
                }
                """,
                inputSymbol,
                generateContextParamBindings(),
                generateStaticContextParamBindings());
    }

    private GoWriter.Writable generateContextParamBindings() {
        return writer -> {
            input.getAllMembers().values().forEach(it -> {
                if (!it.hasTrait(ContextParamTrait.class)) {
                    return;
                }

                var contextParam = it.expectTrait(ContextParamTrait.class);
                writer.write("p.$L = in.$L", contextParam.getName(), it.getMemberName());
            });
        };
    }

    private GoWriter.Writable generateStaticContextParamBindings() {
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
                writer.writeInline("$T($S)", SmithyGoTypes.Ptr.String, value.expectStringNode().getValue());
            } else if (value.isBooleanNode()) {
                writer.writeInline("$T($L)", SmithyGoTypes.Ptr.Bool, value.expectBooleanNode().getValue());
            } else {
                throw new CodegenException("unrecognized static context param value type");
            }
        };
    }
}
