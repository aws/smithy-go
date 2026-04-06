package software.amazon.smithy.go.codegen;

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import software.amazon.smithy.go.codegen.util.ShapeUtil;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.ServiceShape;
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

	// TODO(serde2): synthetic shapes (smithy.go.synthetic namespace) can
	// collide with modeled shapes that happen to have the same name (e.g.
	// ConverseOutput is both a modeled union and a synthetic operation output
	// in bedrockruntime). For now just throw a prefix on there so it doesn't
	// conflict but I don't like that.
    private static final String SYNTHETIC_NAMESPACE = "smithy.go.synthetic";

    public static String getSchemaName(Shape shape, ServiceShape service) {
        var name = shape.getId().getName(service);
        if (shape.getId().getNamespace().equals(SYNTHETIC_NAMESPACE)) {
            name = "SmithyGoSynthetic_" + name;
        }
        return ShapeUtil.isExported(shape)
                ? name
                : "_" + name;
    }

    public static String getMemberSchemaName(Shape shape, MemberShape member, ServiceShape service) {
        return String.format("%s_%s", getSchemaName(shape, service), member.getMemberName());
    }

    @Override
    public void accept(GoWriter writer) {
        var service = ctx.service();
        writer.addUseImports(SmithyGoDependency.SMITHY);
        writer.writeGoTemplate("""
                        var $ident:L = smithy.NewSchema(smithy.ShapeID{
                            Namespace: $namespace:S,
                            Name: $name:S,
                        }, smithy.ShapeType$type:L, $numMembers:L$traits:W)
                        $memberVarDecls:W
                        """,
                Map.of(
                        "ident", getSchemaName(shape, service),
                        "namespace", shape.getId().getNamespace(),
                        "name", shape.getId().getName(),
                        "type", StringUtils.capitalize(shape.getType().toString()),
                        "numMembers", shape.members().size(),
                        "traits", renderVariadicTraits(shape.getAllTraits().values()),
                        "memberVarDecls", Writable.map(shape.members(), this::renderMemberVarDecl, true)
                ));
    }

    public void acceptMembersInit(GoWriter writer) {
        if (shape.members().isEmpty()) {
            return;
        }
        writer.writeGoTemplate("""
                        $memberAddAndAssign:W
                        """,
                Map.of(
                        "memberAddAndAssign", Writable.map(shape.members(), this::renderMemberAddAndAssign, true)
                ));
    }

    private Writable renderMemberVarDecl(MemberShape member) {
        return goTemplate("""
                        var $ident:L *smithy.Schema
                        """,
                Map.of("ident", getMemberSchemaName(shape, member, ctx.service())));
    }

    private Writable renderMemberAddAndAssign(MemberShape member) {
        var target = ctx.model().expectShape(member.getTarget());
        var service = ctx.service();
        var memberTraits = member.getAllTraits().values();

        return goTemplate("""
                        $ident:L = $schema:L.AddMember($name:S, $target:L$traits:W)
                        """,
                Map.of(
                        "ident", getMemberSchemaName(shape, member, service),
                        "schema", getSchemaName(shape, service),
                        "name", member.getMemberName(),
                        "target", getSchemaName(target, service),
                        "traits", renderVariadicTraits(memberTraits)
                ));
    }

    private Writable renderVariadicTraits(java.util.Collection<Trait> traits) {
        if (traits.isEmpty()) {
            return emptyGoTemplate();
        }
        return goTemplate(", $W", Writable.map(traits, this::renderVariadicTrait));
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
