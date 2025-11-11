package software.amazon.smithy.go.codegen;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public class SchemaGenerator implements GoWriter.Writable {
    private final GoCodegenContext ctx;
    private final StructureShape shape;

    public SchemaGenerator(GoCodegenContext ctx, StructureShape shape) {
        this.ctx = ctx;
        this.shape = shape;
    }

    @Override
    public void accept(GoWriter writer) {
        writer.write(goTemplate("""
                var $schemaName:L = encoding.NewSchema($shapeId:S)
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
                var $schemaName:L = encoding.NewMemberSchema($member:S)
                """,
                Map.of(
                        "schemaName", schemaName(shape, member),
                        "member", memberName
                ));
    }

    public static String schemaName(StructureShape shape) {
        return String.format("Schema_%s", shape.getId().getName());
    }

    public static String schemaName(StructureShape shape, MemberShape member) {
        return String.format("Schema_%s_%s", shape.getId().getName(), member.getMemberName());
    }
}
