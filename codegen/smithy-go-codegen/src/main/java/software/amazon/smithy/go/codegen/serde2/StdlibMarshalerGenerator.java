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

package software.amazon.smithy.go.codegen.serde2;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SchemaGenerator;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;

/**
 * Generates stdlib {@code encoding/json} {@code MarshalJSON} / {@code UnmarshalJSON}
 * methods for a structure or union member type, delegating to
 * {@code smithyjson.Codec}. Emitted only when the {@code stdlibMarshalers}
 * setting enables the "json" format.
 */
public final class StdlibMarshalerGenerator implements Writable {
    private final GoCodegenContext ctx;
    private final Symbol symbol;
    private final Writable value;
    private final Writable target;

    /**
     * Creates a generator for a plain structure (or error structure): the
     * codec marshals/unmarshals the value directly.
     *
     * @param ctx    The codegen context.
     * @param symbol The Go symbol of the structure.
     */
    public StdlibMarshalerGenerator(GoCodegenContext ctx, Symbol symbol) {
        this.ctx = ctx;
        this.symbol = symbol;
        this.value = goTemplate("v");
        this.target = goTemplate("v");
    }

    /**
     * Creates a generator for a union member type: the codec marshals/
     * unmarshals a {@code smithy.UnionVariantSerializer} /
     * {@code smithy.UnionVariantDeserializer} wrapping {@code v}, so that the
     * union envelope is present around the standalone variant value.
     *
     * @param ctx      The codegen context.
     * @param symbol   The Go symbol of the union member type.
     * @param union    The union shape the member belongs to.
     * @param member   The union member.
     */
    public StdlibMarshalerGenerator(GoCodegenContext ctx, Symbol symbol, Shape union, MemberShape member) {
        this.ctx = ctx;
        this.symbol = symbol;

        var unionSchema = SchemaGenerator.getSchemaRef(union, ctx.service());
        var variantSchema = SchemaGenerator.getMemberSchemaRef(union, member, ctx.service());
        this.value = goTemplate("""
                $unionVariantSerializer:T{
                    Union:   $union:L,
                    Variant: $variant:L,
                    Value:   v,
                }""",
                Map.of(
                        "unionVariantSerializer", SmithyGoDependency.SMITHY.valueSymbol("UnionVariantSerializer"),
                        "union", unionSchema,
                        "variant", variantSchema
                ));
        this.target = goTemplate("""
                $unionVariantDeserializer:T{
                    Union:   $union:L,
                    Variant: $variant:L,
                    Value:   v,
                }""",
                Map.of(
                        "unionVariantDeserializer", SmithyGoDependency.SMITHY.valueSymbol("UnionVariantDeserializer"),
                        "union", unionSchema,
                        "variant", variantSchema
                ));
    }

    @Override
    public void accept(GoWriter writer) {
        writer.writeGoTemplate("""
                func (v *$symbol:L) MarshalJSON() ([]byte, error) {
                    return $codec:W.Marshal($value:W)
                }

                func (v *$symbol:L) UnmarshalJSON(p []byte) error {
                    return $codec:W.Unmarshal(p, $target:W)
                }

                var _ $marshaler:T = (*$symbol:L)(nil)
                var _ $unmarshaler:T = (*$symbol:L)(nil)
                """,
                Map.of(
                        "symbol", symbol.getName(),
                        "codec", codecLiteral(),
                        "value", value,
                        "target", target,
                        "marshaler", GoStdlibTypes.Encoding.Json.Marshaler,
                        "unmarshaler", GoStdlibTypes.Encoding.Json.Unmarshaler
                ));
    }

    private Writable codecLiteral() {
        return goTemplate("$codec:T{Options: $options:T{UseJSONName: true}}",
                Map.of(
                        "codec", SmithyGoDependency.SMITHY_JSON.valueSymbol("Codec"),
                        "options", SmithyGoDependency.SMITHY_JSON.valueSymbol("CodecOptions")
                ));
    }
}
