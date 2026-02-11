package software.amazon.smithy.go.codegen.serde2;

import java.util.Comparator;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SchemaGenerator;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.UnsupportedShapeException;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.knowledge.GoPointableIndex;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;

public class StructureDeserializer implements Writable {
    private final GoCodegenContext ctx;
    private final StructureShape shape;
    private final GoPointableIndex nilIndex;

    public StructureDeserializer(GoCodegenContext ctx, StructureShape shape) {
        this.ctx = ctx;
        this.shape = shape;

        this.nilIndex = GoPointableIndex.of(ctx.model());
    }

    @Override
    public void accept(GoWriter writer) {
        writer.addImport(ctx.settings().getModuleName() + "/schemas", "schemas");
        writer.addUseImports(SmithyGoDependency.SMITHY);

        var symbol = ctx.symbolProvider().toSymbol(shape);
        var members = shape.members().stream()
                .sorted(Comparator.comparing(MemberShape::getMemberName))
                .toList();
        writer.openBlock("func (v *$L) Deserialize(d smithy.ShapeDeserializer) error {", "}", symbol.getName(), () -> {
            writer.openBlock("return smithy.ReadStruct(d, schemas.$L, func(s *smithy.Schema) error {", "})", SchemaGenerator.getSchemaName(shape), () -> {
                writer.openBlock("switch s {", "}", () -> {
                    for (var member : members) {
                        writer.write("case schemas.$L:", SchemaGenerator.getMemberSchemaName(shape, member));
                        renderMember(writer, member, ctx.model().expectShape(member.getTarget()), "v." + ctx.symbolProvider().toMemberName(member));
                    }
                });
                writer.write("return nil");
            });
        });
    }

    private void renderMember(GoWriter writer, MemberShape member, Shape target, String ident) {
        var schemaName = SchemaGenerator.getMemberSchemaName(shape, member);
        var ptrSuffix = nilIndex.isNillable(member) ? "Ptr" : "";
        switch (target.getType()) {
            case INTEGER ->
                    writer.write("return d.ReadInt32$L(schemas.$L, &$L)", ptrSuffix, schemaName, ident);
            case STRING ->
                    writer.write("return d.ReadString$L(schemas.$L, &$L)", ptrSuffix, schemaName, ident);
            case BOOLEAN ->
                    writer.write("return d.ReadBool$L(schemas.$L, &$L)", ptrSuffix, schemaName, ident);
            case LIST, MAP ->
                    writer.write("return deserialize$L(d, schemas.$L, &$L)", target.getId().getName(), schemaName, ident);

            // FUTURE(602)
            case BIG_INTEGER, BIG_DECIMAL -> throw new UnsupportedShapeException(target.getType());

            // invalid in this context
            case MEMBER, SERVICE, RESOURCE, OPERATION -> throw new UnsupportedShapeException(target.getType());

            default -> writer.write("return nil // TODO " + target.getType());
        }
    }
}
