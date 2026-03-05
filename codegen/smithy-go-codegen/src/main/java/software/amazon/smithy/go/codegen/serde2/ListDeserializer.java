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
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.SparseTrait;

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
        if (member.hasTrait(SparseTrait.class)) {
            renderSparse(writer);
        } else {
            renderDense(writer);
        }
    }

    private void renderDense(GoWriter writer) {
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
                "memberSymbol", switch (member.getType()) {
                    case ENUM -> GoUniverseTypes.String;
                    case INT_ENUM -> GoUniverseTypes.Int32;
                    default -> ctx.symbolProvider().toSymbol(member);
                },
                "deserializeMember", renderDeserializeMember(),
                "cast", switch (member.getType()) {
                    case ENUM, INT_ENUM -> goTemplate("$T(vv)", ctx.symbolProvider().toSymbol(member));
                    default -> goTemplate("vv");
                }
        ));
    }

    private void renderSparse(GoWriter writer) {
        writer.writeGoTemplate("""
                func deserialize$shapeName:L(d smithy.ShapeDeserializer, s *smithy.Schema, v *$symbol:T) error {
                    return smithy.ReadList(d, s, func() error {
                        if isNil, err := d.ReadNil(s.ListMember()); err != nil {
                            return err
                        } else if isNil {
                            *v = append(*v, nil)
                            return nil
                        }

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
                "memberSymbol", switch (member.getType()) {
                    case ENUM -> GoUniverseTypes.String;
                    case INT_ENUM -> GoUniverseTypes.Int32;
                    default -> ctx.symbolProvider().toSymbol(member);
                },
                "deserializeMember", renderDeserializeMember(),
                "cast", renderSparseCast()
        ));
    }

    private Writable renderSparseCast() {
        return switch (member.getType()) {
            case ENUM, INT_ENUM -> goTemplate("""
                    func() $T {
                        ev := $T(vv)
                        return &ev
                    }()""", ctx.symbolProvider().toSymbol(member));

            // don't need the address-of
            case BLOB, LIST, SET, MAP, UNION, DOCUMENT ->
                    goTemplate("vv");

            default -> goTemplate("&vv");
        };
    }

    private Writable renderDeserializeMember() {
        return switch (member.getType()) {
            case BYTE ->
                    goTemplate("d.ReadInt8(s.ListMember(), &vv)");
            case SHORT ->
                    goTemplate("d.ReadInt16(s.ListMember(), &vv)");
            case INTEGER, INT_ENUM ->
                    goTemplate("d.ReadInt32(s.ListMember(), &vv)");
            case LONG ->
                    goTemplate("d.ReadInt64(s.ListMember(), &vv)");

            case FLOAT ->
                    goTemplate("d.ReadFloat32(s.ListMember(), &vv)");
            case DOUBLE ->
                    goTemplate("d.ReadFloat64(s.ListMember(), &vv)");

            case STRING, ENUM ->
                    goTemplate("d.ReadString(s.ListMember(), &vv)");
            case BOOLEAN ->
                    goTemplate("d.ReadBool(s.ListMember(), &vv)");
            case TIMESTAMP ->
                    goTemplate("d.ReadTimestamp(s.ListMember(), &vv)");
            case BLOB ->
                    goTemplate("d.ReadBlob(s.ListMember(), &vv)");

            case LIST, SET, MAP, UNION ->
                    goTemplate("deserialize$L(d, s.ListMember(), &vv)", member.getId().getName());
            case STRUCTURE ->
                    goTemplate("vv.Deserialize(d)");
            case DOCUMENT ->
                    goTemplate("d.ReadDocument(s.ListMember(), &vv)");

            case BIG_INTEGER, BIG_DECIMAL ->
                    throw new CodegenException("big integer / big decimal unsupported");
            case MEMBER, OPERATION, RESOURCE, SERVICE ->
                    throw new CodegenException("invalid shape type " + shape.getType());
        };
    }
}
