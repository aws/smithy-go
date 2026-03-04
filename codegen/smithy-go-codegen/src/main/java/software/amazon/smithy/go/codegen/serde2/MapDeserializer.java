package software.amazon.smithy.go.codegen.serde2;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoUniverseTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.util.ShapeUtil;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.Shape;

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
                    case ENUM -> GoUniverseTypes.String;
                    case INT_ENUM -> GoUniverseTypes.Int32;
                    default -> ctx.symbolProvider().toSymbol(value);
                },
                "deserializeValue", renderDeserializeValue(),
                "cast", switch (value.getType()) {
                    case ENUM, INT_ENUM -> goTemplate("$T(vv)", ctx.symbolProvider().toSymbol(value));
                    default -> goTemplate("vv");
                }
        ));
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
                    goTemplate("d.ReadTimestamp(s.MapValue(), &vv)");
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
