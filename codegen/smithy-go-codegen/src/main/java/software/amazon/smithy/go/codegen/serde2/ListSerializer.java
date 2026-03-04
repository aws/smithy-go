package software.amazon.smithy.go.codegen.serde2;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import software.amazon.smithy.codegen.core.CodegenException;
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
            case BYTE ->
                    goTemplate("s.WriteInt8(schema.ListMember(), vv)");
            case SHORT ->
                    goTemplate("s.WriteInt16(schema.ListMember(), vv)");
            case INTEGER, INT_ENUM ->
                    goTemplate("s.WriteInt32(schema.ListMember(), int32(vv))");
            case LONG ->
                    goTemplate("s.WriteInt64(schema.ListMember(), vv)");

            case FLOAT ->
                    goTemplate("s.WriteFloat32(schema.ListMember(), vv)");
            case DOUBLE ->
                    goTemplate("s.WriteFloat64(schema.ListMember(), vv)");

            case STRING, ENUM ->
                    goTemplate("s.WriteString(schema.ListMember(), string(vv))");
            case BOOLEAN ->
                    goTemplate("s.WriteBool(schema.ListMember(), vv)");
            case TIMESTAMP ->
                    goTemplate("s.WriteTime(schema.ListMember(), vv)");
            case BLOB ->
                    goTemplate("s.WriteBlob(schema.ListMember(), vv)");

            case LIST, SET, MAP, UNION ->
                    goTemplate("serialize$L(s, schema.ListMember(), vv)", member.getId().getName());
            case STRUCTURE ->
                    goTemplate("vv.Serialize(s)");
            case DOCUMENT ->
                    goTemplate("s.WriteDocument(schema.ListMember(), vv)");

            case BIG_INTEGER, BIG_DECIMAL ->
                    throw new CodegenException("big integer / big decimal unsupported");
            case MEMBER, OPERATION, RESOURCE, SERVICE ->
                    throw new CodegenException("invalid shape type " + member.getType());
        };
    }
}
