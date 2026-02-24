package software.amazon.smithy.go.codegen.serde2;

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
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
                "valueSymbol", value.isEnumShape() ? GoUniverseTypes.String : ctx.symbolProvider().toSymbol(value),
                "deserializeValue", renderDeserializeValue(),
                "cast", value.isEnumShape() ? goTemplate("$T(vv)", ctx.symbolProvider().toSymbol(value)) : goTemplate("vv")
        ));
    }

    private Writable renderDeserializeValue() {
        return switch (value.getType()) {
            case STRING, ENUM -> goTemplate("d.ReadString(nil, &vv)");
            case STRUCTURE -> goTemplate("vv.Deserialize(d)");
            case BLOB -> goTemplate("d.ReadBlob(nil, &vv)");
            case INTEGER -> goTemplate("d.ReadInt32(nil, &vv)");
            case LONG -> goTemplate("d.ReadInt64(nil, &vv)");
            case FLOAT -> goTemplate("d.ReadFloat32(nil, &vv)");
            case DOUBLE -> goTemplate("d.ReadFloat64(nil, &vv)");
            case BOOLEAN -> goTemplate("d.ReadBool(nil, &vv)");
            case UNION, MAP, LIST -> goTemplate("deserialize$L(d, nil, &vv)", value.getId().getName());
            default -> emptyGoTemplate();
        };
    }
}
