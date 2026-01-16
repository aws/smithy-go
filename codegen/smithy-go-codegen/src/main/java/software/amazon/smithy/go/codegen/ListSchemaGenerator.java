package software.amazon.smithy.go.codegen;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public class ListSchemaGenerator implements GoWriter.Writable {
    private final GoCodegenContext ctx;
    private final ListShape shape;

    public ListSchemaGenerator(GoCodegenContext ctx, ListShape shape) {
        this.ctx = ctx;
        this.shape = shape;
    }

    public static String schemaName(ListShape shape) {
        return shape.getId().getName();
    }

    public static String memberSchemaName(ListShape shape) {
        return String.format("%s_Member", schemaName(shape));
    }

    @Override
    public void accept(GoWriter writer) {
        writer.addUseImports(SmithyGoDependency.SMITHY);
        writer.write(goTemplate("""
                var $schema:L = smithy.NewSchema($shapeId:S)
                var $memberSchema:L = smithy.NewMemberSchema("member")
                """,
                Map.of(
                        "schema", schemaName(shape),
                        "memberSchema", memberSchemaName(shape),
                        "shapeId", shape.getId().toString()
                )));
    }
}


