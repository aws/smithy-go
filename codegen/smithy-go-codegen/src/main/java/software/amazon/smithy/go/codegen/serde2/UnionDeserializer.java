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

public class UnionDeserializer implements Writable {
    private final GoCodegenContext ctx;
    private final UnionShape shape;

    public UnionDeserializer(GoCodegenContext ctx, UnionShape shape) {
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
                func deserialize$shapeName:L(d smithy.ShapeDeserializer, s *smithy.Schema, v *$symbol:T) error {
                    ms, err := d.ReadUnion(s)
                    if err != nil {
                        return err
                    }

                    switch ms {
                    $cases:W
                    }
                    return nil
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
                
                var memberSymbol = unionSymbol.toBuilder()
                        .name(memberName)
                        .build();
                
                w.write("case schemas.$L:", variantSchema);
                w.indent();
                w.write("vv := &$T{}", memberSymbol);
                w.write("*v = vv");
                w.write("return vv.Deserialize(d)");
                w.dedent();
            }
        };
    }
}
