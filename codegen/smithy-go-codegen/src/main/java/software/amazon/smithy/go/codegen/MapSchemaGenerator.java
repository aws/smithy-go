package software.amazon.smithy.go.codegen;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public class MapSchemaGenerator implements GoWriter.Writable {
    private final GoCodegenContext ctx;
    private final MapShape shape;

    public MapSchemaGenerator(GoCodegenContext ctx, MapShape shape) {
        this.ctx = ctx;
        this.shape = shape;
    }

    public static String schemaName(MapShape shape) {
        return shape.getId().getName();
    }

    public static String keySchemaName(MapShape shape) {
        return String.format("%s_Key", schemaName(shape));
    }

    public static String valueSchemaName(MapShape shape) {
        return String.format("%s_Value", schemaName(shape));
    }

    @Override
    public void accept(GoWriter writer) {
        writer.addUseImports(SmithyGoDependency.SMITHY);
        writer.write(goTemplate("""
                var $schema:L = smithy.NewSchema($shapeId:S)
                var $keySchema:L = smithy.NewMemberSchema("key")
                var $valueSchema:L = smithy.NewMemberSchema("value")
                """,
                Map.of(
                        "schema", schemaName(shape),
                        "keySchema", keySchemaName(shape),
                        "valueSchema", valueSchemaName(shape),
                        "shapeId", shape.getId().toString()
                )));
    }
}


