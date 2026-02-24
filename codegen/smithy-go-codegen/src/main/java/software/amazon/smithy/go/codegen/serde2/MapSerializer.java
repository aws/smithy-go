package software.amazon.smithy.go.codegen.serde2;

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.util.ShapeUtil;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.Shape;

public class MapSerializer implements Writable {
    private final GoCodegenContext ctx;
    private final MapShape shape;
    private final Shape value;

    public MapSerializer(GoCodegenContext ctx, MapShape shape) {
        this.ctx = ctx;
        this.shape = shape;
        this.value = ShapeUtil.expectMember(ctx.model(), shape);
    }

    @Override
    public void accept(GoWriter writer) {
        writer.addUseImports(SmithyGoDependency.SMITHY);
        writer.writeGoTemplate("""
                func serialize$shapeName:L(s smithy.ShapeSerializer, schema *smithy.Schema, v $symbol:T) {
                    s.WriteMap(schema)
                    for k, vv := range v {
                        s.WriteKey(nil, k)
                        $serializeValue:W
                    }
                    s.CloseMap()
                }
                """, Map.of(
                "shapeName", shape.getId().getName(),
                "symbol", ctx.symbolProvider().toSymbol(shape),
                "serializeValue", renderSerializeValue()
        ));
    }

    private Writable renderSerializeValue() {
        return switch (value.getType()) {
            case BYTE -> goTemplate("s.WriteInt8(nil, vv)");
            case SHORT -> goTemplate("s.WriteInt16(nil, vv)");
            case INTEGER -> goTemplate("s.WriteInt32(nil, vv)");
            case LONG -> goTemplate("s.WriteInt64(nil, vv)");
            case FLOAT -> goTemplate("s.WriteFloat32(nil, vv)");
            case DOUBLE -> goTemplate("s.WriteFloat64(nil, vv)");
            case BOOLEAN -> goTemplate("s.WriteBool(nil, vv)");
            case STRING -> goTemplate("s.WriteString(nil, vv)");
            case ENUM -> goTemplate("s.WriteString(nil, string(vv))");
            case BLOB -> goTemplate("s.WriteBlob(nil, vv)");
            case TIMESTAMP -> goTemplate("s.WriteTime(nil, vv)");
            case BIG_INTEGER -> goTemplate("s.WriteBigInteger(nil, vv)");
            case BIG_DECIMAL -> goTemplate("s.WriteBigDecimal(nil, vv)");
            case STRUCTURE -> goTemplate("vv.Serialize(s)");
            case LIST, MAP, UNION -> goTemplate("serialize$L(s, nil, vv)", value.getId().getName());
            default -> emptyGoTemplate();
        };
    }
}
