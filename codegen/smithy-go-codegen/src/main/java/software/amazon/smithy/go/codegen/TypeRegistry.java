package software.amazon.smithy.go.codegen;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
import software.amazon.smithy.model.knowledge.EventStreamIndex;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.ErrorTrait;

public class TypeRegistry implements Writable {
    private final GoCodegenContext ctx;

    public TypeRegistry(GoCodegenContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void accept(GoWriter writer) {
        var model = ctx.model();
        var service = ctx.service();

        // Errors: needed for DeserializableError lookup
        var shapes = new HashSet<StructureShape>();
        var operationIndex = OperationIndex.of(model);
        for (var op : TopDownIndex.of(model).getContainedOperations(service)) {
            shapes.addAll(operationIndex.getErrors(op, service));
        }

        // Event stream events: needed for LookupEntry by target ID
        var streamIndex = EventStreamIndex.of(model);
        for (var op : TopDownIndex.of(model).getContainedOperations(service)) {
            streamIndex.getInputInfo(op).ifPresent(info ->
                    info.getEventStreamTarget().members().forEach(m ->
                            shapes.add(model.expectShape(m.getTarget(), StructureShape.class))));
            streamIndex.getOutputInfo(op).ifPresent(info ->
                    info.getEventStreamTarget().members().forEach(m ->
                            shapes.add(model.expectShape(m.getTarget(), StructureShape.class))));
        }

        var sorted = shapes.stream().sorted().collect(Collectors.toList());

        writer.addUseImports(SmithyGoDependency.SMITHY);
        if (sorted.stream().anyMatch(Prelude::isPublicPreludeShape)) {
            writer.addUseImports(SmithyGoDependency.SMITHY_PRELUDE);
        }
        if (sorted.stream().anyMatch(s -> !Prelude.isPublicPreludeShape(s))) {
            writer.addImport(ctx.settings().getModuleName() + "/schemas", "schemas");
        }
        writer.writeGoTemplate("""
                // TypeRegistry is the type registry for this service.
                var TypeRegistry = &smithy.TypeRegistry{
                    Entries: map[string]*smithy.TypeRegistryEntry{
                        $entries:W
                    },
                }
                """, Map.of("entries", Writable.map(sorted, this::renderEntry)));
    }

    private Writable renderEntry(StructureShape shape) {
        return goTemplate("""
                $S: &smithy.TypeRegistryEntry{
                    Schema: $L,
                    New: func() any { return &$T{} },
                },""", shape.getId().toString(), SchemaGenerator.getSchemaRef(shape, ctx.service()), ctx.symbolProvider().toSymbol(shape));
    }
}
