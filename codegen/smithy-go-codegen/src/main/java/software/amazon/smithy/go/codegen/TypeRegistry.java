package software.amazon.smithy.go.codegen;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import software.amazon.smithy.model.shapes.StructureShape;

public class TypeRegistry implements Writable {
    private final GoCodegenContext ctx;

    public TypeRegistry(GoCodegenContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void accept(GoWriter writer) {
        var shapes = ctx.serdeShapes(StructureShape.class).stream().toList();

        writer.addUseImports(SmithyGoDependency.SMITHY);
        writer.addImport(ctx.settings().getModuleName() + "/schemas", "schemas");
        writer.writeGoTemplate("""
                // TypeRegistry is the type registry for this service.
                var TypeRegistry = &smithy.TypeRegistry{
                    Entries: map[string]*smithy.TypeRegistryEntry{
                        $entries:W
                    },
                }
                """, Map.of("entries", Writable.map(shapes, this::renderEntry)));
    }

    private Writable renderEntry(StructureShape shape) {
        return goTemplate("""
                $S: &smithy.TypeRegistryEntry{
                    Schema: schemas.$L,
                    New: func() any { return &$T{} },
                },""", shape.getId().toString(), SchemaGenerator.getSchemaName(shape, ctx.service()), ctx.symbolProvider().toSymbol(shape));
    }
}
