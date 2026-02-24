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
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.Shape;

public class ListDeserializer implements Writable {
    private final GoCodegenContext ctx;
    private final ListShape shape;
    private final Shape member;

    public ListDeserializer(GoCodegenContext ctx, ListShape shape) {
        this.ctx = ctx;
        this.shape = shape;
        this.member = ShapeUtil.expectMember(ctx.model(), shape);
    }

    @Override
    public void accept(GoWriter writer) {
        writer.addUseImports(SmithyGoDependency.SMITHY);
        writer.writeGoTemplate("""
                func deserialize$shapeName:L(d smithy.ShapeDeserializer, s *smithy.Schema, v *$symbol:T) error {
                    return smithy.ReadList(d, s, func() error {
                        var vv $memberSymbol:T
                        if err := $deserializeMember:W; err != nil {
                            return err
                        }

                        *v = append(*v, $cast:W)
                        return nil
                    })
                }
                """, Map.of(
                "shapeName", shape.getId().getName(),
                "symbol", ctx.symbolProvider().toSymbol(shape),
                "memberSymbol", member.isEnumShape() ? GoUniverseTypes.String : ctx.symbolProvider().toSymbol(member),
                "deserializeMember", renderDeserializeMember(),
                "cast", member.isEnumShape() ? goTemplate("$T(vv)", ctx.symbolProvider().toSymbol(member)) : goTemplate("vv")
        ));
    }

    private Writable renderDeserializeMember() {
        return switch (member.getType()) {
            case STRING, ENUM -> goTemplate("d.ReadString(nil, &vv)");
            case STRUCTURE -> goTemplate("vv.Deserialize(d)");
            case BLOB -> goTemplate("d.ReadBlob(nil, &vv)");
            case INTEGER -> goTemplate("d.ReadInt32(nil, &vv)");
            case LONG -> goTemplate("d.ReadInt64(nil, &vv)");
            case FLOAT -> goTemplate("d.ReadFloat32(nil, &vv)");
            case DOUBLE -> goTemplate("d.ReadFloat64(nil, &vv)");
            case BOOLEAN -> goTemplate("d.ReadBool(nil, &vv)");
            case UNION, MAP -> goTemplate("deserialize$L(d, nil, &vv)", member.getId().getName());
            default -> emptyGoTemplate();
        };
    }
}
