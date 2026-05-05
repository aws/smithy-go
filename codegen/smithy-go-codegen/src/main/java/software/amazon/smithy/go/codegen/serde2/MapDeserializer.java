package software.amazon.smithy.go.codegen.serde2;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoUniverseTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.ProtocolDocumentGenerator;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.util.ShapeUtil;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.SparseTrait;

public class MapDeserializer implements Writable {
    private final GoCodegenContext ctx;
    private final MapShape shape;
    private final Shape value;

    public MapDeserializer(GoCodegenContext ctx, MapShape shape) {
        this.ctx = ctx;
        this.shape = shape;
        this.value = ShapeUtil.expectMember(ctx.model(), shape);
    }

    @Override
    public void accept(GoWriter writer) {
        writer.addUseImports(SmithyGoDependency.SMITHY);
        if (shape.hasTrait(SparseTrait.class)) {
            renderSparse(writer);
        } else {
            renderDense(writer);
        }
    }

    private void renderDense(GoWriter writer) {
        writer.writeGoTemplate("""
                func deserialize$shapeName:L(d smithy.ShapeDeserializer, s *smithy.Schema, v *$symbol:T) error {
                    *v = make($symbol:T)
                    return smithy.ReadMap(d, s, func(k string) error {
                        var vv $valueSymbol:T
                        if err := $deserializeValue:W; err != nil {
                            return err
                        }

                        (*v)[k] = $cast:W
                        return nil
                    })
                }
                """, Map.of(
                "shapeName", shape.getId().getName(),
                "symbol", ctx.symbolProvider().toSymbol(shape),
                "valueSymbol", switch (value.getType()) {
                    case STRING, ENUM -> ShapeUtil.isEnum(value)
                            ? GoUniverseTypes.String : ctx.symbolProvider().toSymbol(value);
                    case INT_ENUM -> GoUniverseTypes.Int32;
                    case DOCUMENT -> SmithyGoDependency.SMITHY_DOCUMENT.valueSymbol("Value");
                    default -> ctx.symbolProvider().toSymbol(value);
                },
                "deserializeValue", renderDeserializeValue(),
                "cast", renderDenseCast()
        ));
    }

    private void renderSparse(GoWriter writer) {
        writer.writeGoTemplate("""
                func deserialize$shapeName:L(d smithy.ShapeDeserializer, s *smithy.Schema, v *$symbol:T) error {
                    *v = make($symbol:T)
                    return smithy.ReadMap(d, s, func(k string) error {
                        if isNil, err := d.ReadNil(s.MapValue()); err != nil {
                            return err
                        } else if isNil {
                            (*v)[k] = nil
                            return nil
                        }

                        var vv $valueSymbol:T
                        if err := $deserializeValue:W; err != nil {
                            return err
                        }

                        (*v)[k] = $cast:W
                        return nil
                    })
                }
                """, Map.of(
                "shapeName", shape.getId().getName(),
                "symbol", ctx.symbolProvider().toSymbol(shape),
                "valueSymbol", switch (value.getType()) {
                    case STRING, ENUM -> ShapeUtil.isEnum(value)
                            ? GoUniverseTypes.String : ctx.symbolProvider().toSymbol(value);
                    case INT_ENUM -> GoUniverseTypes.Int32;
                    case DOCUMENT -> SmithyGoDependency.SMITHY_DOCUMENT.valueSymbol("Value");
                    default -> ctx.symbolProvider().toSymbol(value);
                },
                "deserializeValue", renderDeserializeValue(),
                "cast", renderSparseCast()
        ));
    }

    private Writable renderSparseCast() {
        return switch (value.getType()) {
            case STRING, ENUM, INT_ENUM -> ShapeUtil.isEnum(value)
                    ? goTemplate("""
                    func() $T {
                        ev := $T(vv)
                        return &ev
                    }()""", ctx.symbolProvider().toSymbol(value))
                    : goTemplate("&vv");

            // don't need the address-of
            case BLOB, LIST, SET, MAP, UNION ->
                    goTemplate("vv");

            case DOCUMENT -> renderDocumentCast();

            default -> goTemplate("&vv");
        };
    }

    private Writable renderDenseCast() {
        return switch (value.getType()) {
            case STRING, ENUM, INT_ENUM -> ShapeUtil.isEnum(value)
                    ? goTemplate("$T(vv)", ctx.symbolProvider().toSymbol(value))
                    : goTemplate("vv");
            case DOCUMENT -> renderDocumentCast();
            default -> goTemplate("vv");
        };
    }

    private Writable renderDocumentCast() {
        var unmarshaler = ProtocolDocumentGenerator.Utilities.getInternalDocumentSymbolBuilder(
                ctx.settings(), ProtocolDocumentGenerator.INTERNAL_NEW_DOCUMENT_UNMARSHALER_FUNC).build();
        return goTemplate("""
                func() $T {
                    if ov, ok := vv.(smithydocument.Opaque); ok {
                        return $T(ov.Value)
                    }
                    return nil
                }()""", ctx.symbolProvider().toSymbol(value), unmarshaler);
    }

    private Writable renderDeserializeValue() {
        return switch (value.getType()) {
            case BYTE ->
                    goTemplate("d.ReadInt8(s.MapValue(), &vv)");
            case SHORT ->
                    goTemplate("d.ReadInt16(s.MapValue(), &vv)");
            case INTEGER, INT_ENUM ->
                    goTemplate("d.ReadInt32(s.MapValue(), &vv)");
            case LONG ->
                    goTemplate("d.ReadInt64(s.MapValue(), &vv)");

            case FLOAT ->
                    goTemplate("d.ReadFloat32(s.MapValue(), &vv)");
            case DOUBLE ->
                    goTemplate("d.ReadFloat64(s.MapValue(), &vv)");

            case STRING, ENUM ->
                    goTemplate("d.ReadString(s.MapValue(), &vv)");
            case BOOLEAN ->
                    goTemplate("d.ReadBool(s.MapValue(), &vv)");
            case TIMESTAMP ->
                    goTemplate("d.ReadTime(s.MapValue(), &vv)");
            case BLOB ->
                    goTemplate("d.ReadBlob(s.MapValue(), &vv)");

            case LIST, SET, MAP, UNION ->
                    goTemplate("deserialize$L(d, s.MapValue(), &vv)", value.getId().getName());
            case STRUCTURE ->
                    goTemplate("vv.Deserialize(d)");
            case DOCUMENT ->
                    goTemplate("d.ReadDocument(s.MapValue(), &vv)");

            case BIG_INTEGER, BIG_DECIMAL ->
                    throw new CodegenException("big integer / big decimal unsupported");
            case MEMBER, OPERATION, RESOURCE, SERVICE ->
                    throw new CodegenException("invalid shape type " + value.getType());
        };
    }
}
