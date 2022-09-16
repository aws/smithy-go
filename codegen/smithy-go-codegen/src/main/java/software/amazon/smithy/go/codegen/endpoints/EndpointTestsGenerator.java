/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goBlockTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goDocTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.joinWritables;
import static software.amazon.smithy.go.codegen.endpoints.EndpointParametersGenerator.getExportedParameterName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.BooleanNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NodeVisitor;
import software.amazon.smithy.model.node.NullNode;
import software.amazon.smithy.model.node.NumberNode;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameter;
import software.amazon.smithy.rulesengine.traits.EndpointTestCase;
import software.amazon.smithy.rulesengine.traits.ExpectedEndpoint;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyBuilder;

public final class EndpointTestsGenerator {
    private final Map<String, Object> commonCodegenArgs;

    private EndpointTestsGenerator(Builder builder) {
        var resolverType = SmithyBuilder.requiredState("resolverType", builder.resolverType);
        var newResolverFn = SmithyBuilder.requiredState("newResolverFn", builder.newResolverFn);
        var parametersType = SmithyBuilder.requiredState("parametersType", builder.parametersType);
        var endpointType = SmithyBuilder.requiredState("endpointType", builder.endpointType);
        var resolveEndpointMethodName = SmithyBuilder.requiredState("resolveEndpointMethodName",
                builder.resolveEndpointMethodName);

        commonCodegenArgs = MapUtils.of(
                "parametersType", parametersType,
                "endpointType", endpointType,
                "resolverType", resolverType,
                "newResolverFn", newResolverFn,
                "resolveEndpointMethodName", resolveEndpointMethodName,
                "fmtErrorf", SymbolUtils.createValueSymbolBuilder("Errorf", SmithyGoDependency.FMT).build()
        );
    }

    public GoWriter.Writable generate(List<Parameter> parameters, List<EndpointTestCase> testCases) {
        List<GoWriter.Writable> writables = new ArrayList<>();

        for (int i = 0; i < testCases.size(); i++) {
            var testCase = testCases.get(i);

            writables.add(goTemplate("""
                            $testCaseDocs:W
                            func TestEndpointCase$caseIdx:L(t *$testingT:T) {
                                $testBody:W
                            }
                            """,
                    commonCodegenArgs,
                    MapUtils.of(
                            "caseIdx", i,
                            "testingT", SymbolUtils.createValueSymbolBuilder("T", SmithyGoDependency.TESTING).build(),
                            "testCaseDocs", generateTestCaseDocs(testCase),
                            "testBody", generateTestCase(parameters, testCase)
                    )
            ));
        }

        return joinWritables(writables, "\n\n");
    }

    private GoWriter.Writable generateTestCaseDocs(EndpointTestCase testCase) {
        if (testCase.getDocumentation().isPresent()) {
            return goDocTemplate(testCase.getDocumentation().get());
        }
        return emptyGoTemplate();
    }

    private GoWriter.Writable generateTestCase(List<Parameter> parameters, EndpointTestCase testCase) {
        return goTemplate("""
                        var params = $parametersType:T{
                            $parameterValues:W
                        }

                        resolver := $newResolverFn:T()
                        result, err := resolver.$resolveEndpointMethodName:L($contextBG:T(), params)
                        _, _ = result, err

                        $expectErr:W

                        $expectEndpoint:W
                        """,
                commonCodegenArgs,
                MapUtils.of(
                        "contextBG",
                        SymbolUtils.createValueSymbolBuilder("Background", SmithyGoDependency.CONTEXT).build(),
                        "parameterValues", generateParameterValues(parameters, testCase),
                        "expectErr", generateExpectError(testCase.getExpect().getError()),
                        "expectEndpoint", generateExpectEndpoint(testCase.getExpect().getEndpoint())
                ));
    }

    private GoWriter.Writable generateParameterValues(List<Parameter> parameters, EndpointTestCase testCase) {
        List<GoWriter.Writable> writables = new ArrayList<>();
        // TODO filter keys based on actual modeled parameters
        Set<String> parameterNames = new TreeSet<>();

        parameters.forEach((p) -> {
            parameterNames.add(getExportedParameterName(p));
        });

        testCase.getParams().getMembers().forEach((key, value) -> {
            var exportedName = getExportedParameterName(key);
            if (parameterNames.contains(exportedName)) {
                writables.add((GoWriter w) -> {
                    w.write("$L: $W,", getExportedParameterName(key), generateParameterValue(value));
                });
            }
        });

        return joinWritables(writables, "\n");
    }

    private GoWriter.Writable generateParameterValue(Node value) {
        return switch (value.getType()) {
            case STRING -> (GoWriter w) -> {
                w.write("$T($S)",
                        SymbolUtils.createValueSymbolBuilder("String", SmithyGoDependency.SMITHY_PTR).build(),
                        value.expectStringNode().getValue());
            };
            case BOOLEAN -> (GoWriter w) -> {
                w.write("$T($L)",
                        SymbolUtils.createValueSymbolBuilder("Bool", SmithyGoDependency.SMITHY_PTR).build(),
                        value.expectBooleanNode().getValue());
            };
            default -> throw new CodegenException("Unhandled member type: " + value.getType());
        };
    }

    GoWriter.Writable generateExpectError(Optional<String> expectErr) {
        if (expectErr.isEmpty()) {
            return goTemplate("""
                    if err != nil {
                        t.Fatalf("expect no error, got %v", err)
                    }
                    """);
        }

        return goTemplate("""
                        if err == nil {
                            t.Fatalf("expect error, got none")
                        }
                        if e, a := $expect:S, err.Error(); !$stringsContains:T(a, e) {
                            t.Errorf("expect %v error in %v", e, a)
                        }
                        """,
                MapUtils.of(
                        "expect", expectErr.get(),
                        "stringsContains", SymbolUtils.createValueSymbolBuilder("Contains",
                                SmithyGoDependency.STRINGS).build()
                ));
    }

    GoWriter.Writable generateExpectEndpoint(Optional<ExpectedEndpoint> expectEndpoint) {
        if (expectEndpoint.isEmpty()) {
            return emptyGoTemplate();
        }

        var endpoint = expectEndpoint.get();

        return goTemplate("""
                        expectEndpoint := $endpointType:T{
                            URI: $expectURL:S,
                            $fields:W
                            $properties:W
                        }

                        if e, a := expectEndpoint.URI, result.URI; e != a{
                            t.Errorf("expect %v URI, got %v", e, a)
                        }

                        $assertFields:W

                        $assertProperties:W
                        """,
                commonCodegenArgs,
                MapUtils.of(
                        "cmpDiff", SymbolUtils.createPointableSymbolBuilder("Diff", SmithyGoDependency.GO_CMP).build(),
                        "expectURL", endpoint.getUrl(),
                        "fields", generateFields(endpoint.getHeaders()),
                        "properties", generateProperties(endpoint.getProperties()),
                        "assertFields", generateAssertFields(),
                        "assertProperties", generateAssertProperties()
                ));
    }

    GoWriter.Writable generateFields(Map<String, List<String>> headers) {
        Map<String, Object> commonArgs = MapUtils.of(
                "newFieldSetFn", SymbolUtils.createValueSymbolBuilder("NewFieldSet",
                        SmithyGoDependency.SMITHY_TRANSPORT).build(),
                "fieldSetType", SymbolUtils.createValueSymbolBuilder("FieldSet",
                        SmithyGoDependency.SMITHY_TRANSPORT).build(),
                "fieldType", SymbolUtils.createValueSymbolBuilder("Field",
                        SmithyGoDependency.SMITHY_TRANSPORT).build(),
                "newFieldFn", SymbolUtils.createValueSymbolBuilder("NewField",
                        SmithyGoDependency.SMITHY_TRANSPORT).build()
        );

        if (headers.isEmpty()) {
            return goTemplate("Fields: $newFieldSetFn:T(),", commonArgs);
        }

        return goBlockTemplate("""
                        Fields: func() *$fieldSetType:T {
                            fieldSet := $newFieldSetFn:T()
                        """, """
                            return fieldSet
                        }(),
                        """, commonArgs,
                (w) -> {
                    headers.forEach((key, values) -> {
                        List<GoWriter.Writable> valueWritables = new ArrayList<>();
                        values.forEach((value) -> {
                            valueWritables.add((GoWriter ww) -> ww.write("$S", value));
                        });

                        w.writeGoTemplate("fieldSet.SetHeader($newFieldFn:T($key:S, $values:W))",
                                commonArgs, MapUtils.of(
                                        "key", key,
                                        "values", joinWritables(valueWritables, ", ")
                                ));
                    });
                });
    }

    GoWriter.Writable generateAssertFields() {
        return goTemplate("""
                        expectHeaders := expectEndpoint.Fields.GetHeaderFields()
                        actualHeaders := result.Fields.GetHeaderFields()
                        if diff := $cmpDiff:T(expectHeaders, actualHeaders,
                            $cmpAllowUnexported:T($fieldType:T{}),
                        ); diff != "" {
                            t.Errorf("expect headers to match\\n%s", diff)
                        }
                        """,
                MapUtils.of(
                        "cmpDiff", SymbolUtils.createPointableSymbolBuilder("Diff", SmithyGoDependency.GO_CMP).build(),
                        "cmpAllowUnexported", SymbolUtils.createPointableSymbolBuilder("AllowUnexported",
                                SmithyGoDependency.GO_CMP).build(),
                        "fieldType", SymbolUtils.createValueSymbolBuilder("Field",
                                SmithyGoDependency.SMITHY_TRANSPORT).build()
                ));
    }

    GoWriter.Writable generateProperties(Map<String, Node> properties) {
        Map<String, Object> commonArgs = MapUtils.of(
                "propertiesType", SymbolUtils.createValueSymbolBuilder("Properties", SmithyGoDependency.SMITHY).build()
        );

        if (properties.isEmpty()) {
            return goTemplate("Properties: $propertiesType:T{},", commonArgs);
        }

        return goBlockTemplate("""
                        Properties: func() $propertiesType:T {
                            var properties $propertiesType:T
                        """, """
                            return properties
                        }(),
                        """, commonArgs,
                (w) -> {
                    properties.forEach((key, value) -> {
                        w.writeGoTemplate("properties.Set($key:S, $value:W)",
                                commonArgs, MapUtils.of(
                                        "key", key,
                                        "value", generateNodeValue(value)
                                ));
                    });
                });
    }

    GoWriter.Writable generateNodeValue(Node value) {
        return value.accept(new NodeVisitor<>() {
            @Override
            public GoWriter.Writable arrayNode(ArrayNode arrayNode) {
                List<GoWriter.Writable> elements = new ArrayList<>();
                arrayNode.getElements().forEach((e) -> {
                    elements.add((GoWriter w) -> w.write("$W,", e.accept(this)));
                });

                return (GoWriter w) -> {
                    w.write("""
                            []interface{}{
                                $W
                            }
                            """, joinWritables(elements, "\n"));
                };
            }

            @Override
            public GoWriter.Writable booleanNode(BooleanNode booleanNode) {
                return (GoWriter w) -> w.write("$L", booleanNode.getValue());
            }

            @Override
            public GoWriter.Writable nullNode(NullNode nullNode) {
                return (GoWriter w) -> w.write("nil");
            }

            @Override
            public GoWriter.Writable numberNode(NumberNode numberNode) {
                return (GoWriter w) -> w.write("$L", numberNode.toString());
            }

            @Override
            public GoWriter.Writable objectNode(ObjectNode objectNode) {
                List<GoWriter.Writable> members = new ArrayList<>();
                objectNode.getMembers().forEach((k, v) -> {
                    members.add((GoWriter w) -> w.write("$S: $W,", k.getValue(), v.accept(this)));
                });

                return (GoWriter w) -> {
                    w.write("""
                            map[string]interface{}{
                                $W
                            }
                            """, joinWritables(members, "\n"));
                };
            }

            @Override
            public GoWriter.Writable stringNode(StringNode stringNode) {
                return (GoWriter w) -> w.write("$S", stringNode.getValue());
            }
        });
    }

    GoWriter.Writable generateAssertProperties() {
        return goTemplate("""
                        if diff := $cmpDiff:T(expectEndpoint.Properties, result.Properties,
                            $cmpAllowUnexported:T($propertiesType:T{}),
                        ); diff != "" {
                            t.Errorf("expect properties to match\\n%s", diff)
                        }
                        """,
                MapUtils.of(
                        "cmpDiff", SymbolUtils.createPointableSymbolBuilder("Diff", SmithyGoDependency.GO_CMP).build(),
                        "cmpAllowUnexported", SymbolUtils.createPointableSymbolBuilder("AllowUnexported",
                                SmithyGoDependency.GO_CMP).build(),
                        "propertiesType", SymbolUtils.createValueSymbolBuilder("Properties",
                                SmithyGoDependency.SMITHY).build()
                ));

    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements SmithyBuilder<EndpointTestsGenerator> {
        private Symbol resolverType;
        private Symbol newResolverFn;
        private Symbol parametersType;
        private Symbol endpointType;
        private String resolveEndpointMethodName;

        private Builder() {
        }

        public Builder endpointType(Symbol endpointType) {
            this.endpointType = endpointType;
            return this;
        }

        public Builder resolverType(Symbol resolverType) {
            this.resolverType = resolverType;
            return this;
        }

        public Builder newResolverFn(Symbol newResolverFn) {
            this.newResolverFn = newResolverFn;
            return this;
        }

        public Builder parametersType(Symbol parametersType) {
            this.parametersType = parametersType;
            return this;
        }

        public Builder resolveEndpointMethodName(String resolveEndpointMethodName) {
            this.resolveEndpointMethodName = resolveEndpointMethodName;
            return this;
        }

        @Override
        public EndpointTestsGenerator build() {
            return new EndpointTestsGenerator(this);
        }
    }
}
