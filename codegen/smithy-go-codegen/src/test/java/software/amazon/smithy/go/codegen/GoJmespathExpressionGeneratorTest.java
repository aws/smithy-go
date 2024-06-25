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

package software.amazon.smithy.go.codegen;

import static org.hamcrest.MatcherAssert.assertThat;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.go.codegen.util.ShapeUtil;
import software.amazon.smithy.jmespath.JmespathExpression;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.ShapeId;

import java.util.Map;

public class GoJmespathExpressionGeneratorTest {
    private static final String TEST_MODEL_STR = """
            $version: "2.0"

            namespace smithy.go.test

            structure Struct {
                simpleShape: String
                objectList: ObjectList
                objectMap: ObjectMap
                nested: NestedStruct
            }

            structure Object {
                key: String
            }

            structure NestedStruct {
                nestedField: String
            }

            list ObjectList {
                member: Object
            }

            map ObjectMap {
                key: String,
                value: Object
            }
            """;

    private static final Model TEST_MODEL = Model.assembler()
            .addUnparsedModel("model.smithy", TEST_MODEL_STR)
            .assemble().unwrap();

    private static final GoSettings TEST_SETTINGS = GoSettings.from(ObjectNode.fromStringMap(Map.of(
            "service", "smithy.go.test#foo",
            "module", "github.com/aws/aws-sdk-go-v2/test"
    )));

    private static GoCodegenContext testContext() {
        return new GoCodegenContext(
                TEST_MODEL, TEST_SETTINGS,
                new SymbolVisitor(TEST_MODEL, TEST_SETTINGS),
                null, null, null
        );
    }

    @Test
    public void testFieldExpression() {
        var expr = "simpleShape";

        var writer = new GoWriter("test");
        var generator = new GoJmespathExpressionGenerator(testContext(), writer,
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                JmespathExpression.parse(expr)
        );
        var actual = generator.generate("input");
        assertThat(actual.shape(), Matchers.equalTo(TEST_MODEL.expectShape(ShapeId.from("smithy.api#String"))));
        assertThat(actual.ident(), Matchers.equalTo("v2"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input
                v2 := v1.SimpleShape
                """));
    }

    @Test
    public void testSubexpression() {
        var expr = "nested.nestedField";

        var writer = new GoWriter("test");
        var generator = new GoJmespathExpressionGenerator(testContext(), writer,
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                JmespathExpression.parse(expr)
        );
        var actual = generator.generate("input");
        assertThat(actual.shape(), Matchers.equalTo(TEST_MODEL.expectShape(ShapeId.from("smithy.api#String"))));
        assertThat(actual.ident(), Matchers.equalTo("v3"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input
                v2 := v1.Nested
                v3 := v2.NestedField
                """));
    }

    @Test
    public void testKeysFunctionExpression() {
        var expr = "keys(objectMap)";

        var writer = new GoWriter("test");
        var generator = new GoJmespathExpressionGenerator(testContext(), writer,
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                JmespathExpression.parse(expr)
        );
        var actual = generator.generate("input");
        assertThat(actual.shape(), Matchers.equalTo(ShapeUtil.listOf(ShapeUtil.STRING_SHAPE)));
        assertThat(actual.ident(), Matchers.equalTo("v3"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input
                v2 := v1.ObjectMap
                var v3 []string
                for k := range v2 {
                    v3 = append(v3, k)
                }
                """));
    }

    @Test
    public void testProjectionExpression() {
        var expr = "objectList[*].key";

        var writer = new GoWriter("test");
        var generator = new GoJmespathExpressionGenerator(testContext(), writer,
                TEST_MODEL.expectShape(ShapeId.from("smithy.go.test#Struct")),
                JmespathExpression.parse(expr)
        );
        var actual = generator.generate("input");
        assertThat(actual.shape(), Matchers.equalTo(
                ShapeUtil.listOf(TEST_MODEL.expectShape(ShapeId.from("smithy.api#String")))));
        assertThat(actual.ident(), Matchers.equalTo("v3"));
        assertThat(writer.toString(), Matchers.containsString("""
                v1 := input
                v2 := v1.ObjectList
                var v3 []*string
                for _, v := range v2 {
                v1 := v
                v2 := v1.Key
                v3 = append(v3, v2)
                }
                """));
    }
}
