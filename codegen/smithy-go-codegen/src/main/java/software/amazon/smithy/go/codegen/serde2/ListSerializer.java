package software.amazon.smithy.go.codegen.serde2;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.util.ShapeUtil;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.Shape;

public class ListSerializer implements Writable {
    private final GoCodegenContext ctx;
    private final ListShape shape;
    private final Shape member;

    public ListSerializer(GoCodegenContext ctx, ListShape shape) {
        this.ctx = ctx;
        this.shape = shape;

        this.member = ShapeUtil.expectMember(ctx.model(), shape);
    }

    @Override
    public void accept(GoWriter writer) {
        writer.addUseImports(SmithyGoDependency.SMITHY);
        writer.writeGoTemplate("""
                func serialize$shapeName:L(s smithy.ShapeSerializer, schema *smithy.Schema, v $symbol:T) {
                    s.WriteList(schema)
                    for _, vv := range v {
                        $serializeValue:W
                    }
                    s.CloseList()
                }
                """, Map.of(
                "shapeName", shape.getId().getName(),
                "symbol", ctx.symbolProvider().toSymbol(shape),
                "serializeValue", renderSerializeValue()
        ));
    }

    // TODO sparse
    private Writable renderSerializeValue() {
        return switch (member.getType()) {
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
            case STRUCTURE -> goTemplate("s.WriteStruct(nil, &vv)");
            case LIST, MAP, UNION -> goTemplate("serialize$L(s, nil, vv)", member.getId().getName());
            default -> goTemplate("// TODO: $L", member.getType());
        };
    }
}
