package software.amazon.smithy.go.codegen.serde2;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Comparator;
import java.util.Map;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SchemaGenerator;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.UnionShape;

public class UnionSerializer implements Writable {
    private final GoCodegenContext ctx;
    private final UnionShape shape;

    public UnionSerializer(GoCodegenContext ctx, UnionShape shape) {
        this.ctx = ctx;
        this.shape = shape;
    }

    @Override
    public void accept(GoWriter writer) {
        writer.addUseImports(SmithyGoDependency.SMITHY);
        writer.addImport(ctx.settings().getModuleName() + "/schemas", "schemas");

        var symbol = ctx.symbolProvider().toSymbol(shape);
        var members = shape.members().stream()
                .sorted(Comparator.comparing(MemberShape::getMemberName))
                .toList();

        writer.writeGoTemplate("""
                func serialize$shapeName:L(s smithy.ShapeSerializer, schema *smithy.Schema, v $symbol:T) {
                    switch vv := v.(type) {
                    $cases:W
                    }
                }
                """, Map.of(
                "shapeName", shape.getId().getName(),
                "symbol", symbol,
                "cases", renderCases(members)
        ));
    }

    private Writable renderCases(java.util.List<MemberShape> members) {
        return (GoWriter w) -> {
            var unionSymbol = ctx.symbolProvider().toSymbol(shape);
            for (var member : members) {
                var memberName = ctx.symbolProvider().toMemberName(member);
                var variantSchema = SchemaGenerator.getMemberSchemaName(shape, member);
                w.write("case *$L:", memberName);
                w.write("    s.WriteUnion(schema, schemas.$L, vv)", variantSchema);
            }
        };
    }
}
