package software.amazon.smithy.go.codegen;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public class StructureSchemaGenerator implements GoWriter.Writable {
    private final GoCodegenContext ctx;
    private final StructureShape shape;

    public StructureSchemaGenerator(GoCodegenContext ctx, StructureShape shape) {
        this.ctx = ctx;
        this.shape = shape;
    }

    public static String schemaName(StructureShape shape) {
        return shape.getId().getName();
    }

    public static String schemaName(StructureShape shape, MemberShape member) {
        return String.format("%s_%s", schemaName(shape), member.getMemberName());
    }

    @Override
    public void accept(GoWriter writer) {
        writer.addUseImports(SmithyGoDependency.SMITHY);
        writer.write(goTemplate("""
                var $schemaName:L = smithy.NewSchema($shapeId:S)
                $members:W
                """,
                Map.of(
                        "schemaName", schemaName(shape),
                        "shapeId", shape.getId().toString(),
                        "members", GoWriter.ChainWritable.of(
                                shape.members().stream()
                                        .map(this::renderMember)
                                        .toList())
                                .compose(false)
                )));
    }

    private GoWriter.Writable renderMember(MemberShape member) {
        var memberName = member.getMemberName();
        return goTemplate("""
                var $schemaName:L = smithy.NewMemberSchema($member:S)
                """,
                Map.of(
                        "schemaName", schemaName(shape, member),
                        "member", memberName
                ));
    }
}
