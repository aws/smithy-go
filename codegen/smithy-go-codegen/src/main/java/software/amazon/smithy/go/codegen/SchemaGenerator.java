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
                        var $ident:L = &smithy.Schema{
                            ID: smithy.ShapeID{
                                Namespace: $namespace:S,
                                Name: $name:S,
                            },
                            Type: smithy.ShapeType$type:L,
                            $members:W
                            $traits:W
                        }
                        $memberVars:W
                        """,
                Map.of(
                        "ident", getSchemaName(shape),
                        "namespace", shape.getId().getNamespace(),
                        "name", shape.getId().getName(),
                        "type", StringUtils.capitalize(shape.getType().toString()),
                        "members", renderMembers(),
                        "traits", renderTraits(),
                        "memberVars", Writable.map(shape.members(), this::renderMemberIdent, true)
                ));
    }

    private Writable renderMembers() {
        if (shape.members().isEmpty()) {
            return emptyGoTemplate();
        }
        return goTemplate("""
                Members: map[string]*smithy.Schema{
                    $W
                },""", Writable.map(shape.members(), this::renderMemberMapEntry));
    }

    private Writable renderTraits() {
        if (shape.getAllTraits().isEmpty()) {
            return emptyGoTemplate();
        }
        return goTemplate("""
                Traits: map[string]smithy.Trait{
                    $W
                },""", Writable.map(shape.getAllTraits().values(), this::renderTraitMapEntry));
    }

    private Writable renderMemberIdent(MemberShape member) {
        var memberName = member.getMemberName();
        return goTemplate("""
                        var $ident:L = $schema:L.Members[$name:S]
                        """,
                Map.of(
                        "ident", getMemberSchemaName(shape, member),
                        "schema", getSchemaName(shape),
                        "name", memberName
                ));
    }

    private Writable renderMemberMapEntry(MemberShape member) {
        var target = ctx.model().expectShape(member.getTarget());
        var memberTraits = member.getAllTraits().values();

        return goTemplate("""
                        $name:S: smithy.NewMember($name:S, $target:L$traits:W),
                        """,
                Map.of(
                        "name", member.getMemberName(),
                        "target", getSchemaName(target),
                        "traits", memberTraits.isEmpty()
                                ? emptyGoTemplate()
                                : goTemplate(", $W", Writable.map(memberTraits, this::renderVariadicTrait))
                ));
    }

    private Writable renderTraitMapEntry(Trait trait) {
        var generator = DefaultTraitGenerators.forTrait(trait.toShapeId());
        return generator != null
                ? goTemplate("$S: $W,", trait.toShapeId().toString(), generator.render(trait))
                : emptyGoTemplate();
    }

    private Writable renderVariadicTrait(Trait trait) {
        var generator = DefaultTraitGenerators.forTrait(trait.toShapeId());
        return generator != null
                ? goTemplate("$W,", generator.render(trait))
                : emptyGoTemplate();
    }
}
