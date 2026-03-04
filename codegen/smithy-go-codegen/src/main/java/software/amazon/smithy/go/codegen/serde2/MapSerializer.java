package software.amazon.smithy.go.codegen.serde2;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import software.amazon.smithy.codegen.core.CodegenException;
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
                        s.WriteKey(schema.MapKey(), k)
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
            case BYTE ->
                    goTemplate("s.WriteInt8(schema.MapValue(), vv)");
            case SHORT ->
                    goTemplate("s.WriteInt16(schema.MapValue(), vv)");
            case INTEGER, INT_ENUM ->
                    goTemplate("s.WriteInt32(schema.MapValue(), int32(vv))");
            case LONG ->
                    goTemplate("s.WriteInt64(schema.MapValue(), vv)");

            case FLOAT ->
                    goTemplate("s.WriteFloat32(schema.MapValue(), vv)");
            case DOUBLE ->
                    goTemplate("s.WriteFloat64(schema.MapValue(), vv)");

            case STRING, ENUM ->
                    goTemplate("s.WriteString(schema.MapValue(), string(vv))");
            case BOOLEAN ->
                    goTemplate("s.WriteBool(schema.MapValue(), vv)");
            case TIMESTAMP ->
                    goTemplate("s.WriteTime(schema.MapValue(), vv)");
            case BLOB ->
                    goTemplate("s.WriteBlob(schema.MapValue(), vv)");

            case LIST, SET, MAP, UNION ->
                    goTemplate("serialize$L(s, schema.MapValue(), vv)", value.getId().getName());
            case STRUCTURE ->
                    goTemplate("vv.Serialize(s)");
            case DOCUMENT ->
                    goTemplate("s.WriteDocument(schema.MapValue(), vv)");

            case BIG_INTEGER, BIG_DECIMAL ->
                    throw new CodegenException("big integer / big decimal unsupported");
            case MEMBER, OPERATION, RESOURCE, SERVICE ->
                    throw new CodegenException("invalid shape type " + value.getType());
        };
    }
}
