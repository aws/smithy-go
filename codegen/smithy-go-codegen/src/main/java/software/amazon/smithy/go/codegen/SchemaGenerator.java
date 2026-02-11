package software.amazon.smithy.go.codegen;

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import software.amazon.smithy.go.codegen.util.ShapeUtil;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.utils.SmithyInternalApi;
import software.amazon.smithy.utils.StringUtils;

@SmithyInternalApi
public class SchemaGenerator implements Writable {
    private final GoCodegenContext ctx;
    private final Shape shape;

    public SchemaGenerator(GoCodegenContext ctx, Shape shape) {
        this.ctx = ctx;
        this.shape = shape;
    }

    public static String getSchemaName(Shape shape) {
        var name = shape.getId().getName();
        return ShapeUtil.isExported(shape)
                ? name
                : "_" + name;
    }

    public static String getMemberSchemaName(Shape shape, MemberShape member) {
        return String.format("%s_%s", getSchemaName(shape), member.getMemberName());
    }

    @Override
    public void accept(GoWriter writer) {
        writer.addUseImports(SmithyGoDependency.SMITHY);
        writer.writeGoTemplate("""
                        var $ident:L = smithy.NewSchema($id:S, smithy.ShapeType$type:L,
                                $memberOpts:W
                                smithy.WithTraits(
                                    $traitOpts:W
                                ),
                            )
                        $members:W
                        """,
                Map.of(
                        "ident", getSchemaName(shape),
                        "id", shape.getId().toString(),
                        "type", StringUtils.capitalize(shape.getType().toString()),
                        "members", Writable.map(shape.members(), this::renderMemberIdent),
                        "memberOpts", Writable.map(shape.members(), this::renderMemberOpt),
                        "traitOpts", Writable.map(shape.getAllTraits().values(), this::renderTraitOpt, true)
                ));
    }

    private Writable renderMemberIdent(MemberShape member) {
        var memberName = member.getMemberName();
        return goTemplate("""
                        var $ident:L = $schema:L.Member($name:S)
                        """,
                Map.of(
                        "ident", getMemberSchemaName(shape, member),
                        "schema", getSchemaName(shape),
                        "name", memberName
                ));
    }

    private Writable renderMemberOpt(MemberShape member) {
        var target = ctx.model().expectShape(member.getTarget());

        return goTemplate("smithy.WithMember($name:S, $target:L, $traits:W),",
                Map.of(
                        "name", member.getMemberName(),
                        "target", getSchemaName(target),
                        "traits", Writable.map(member.getAllTraits().values(), this::renderTraitOpt)
                ));
    }

    private Writable renderTraitOpt(Trait trait) {
        // FUTURE: load additional generators through GoIntegration
        var generator = DefaultTraitGenerators.forTrait(trait.toShapeId());
        return generator != null
                ? goTemplate("$W,", generator.render(trait))
                : emptyGoTemplate();
    }
}
