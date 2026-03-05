package software.amazon.smithy.go.codegen.serde2;

import java.util.Comparator;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SchemaGenerator;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.UnsupportedShapeException;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.knowledge.GoPointableIndex;
import software.amazon.smithy.go.codegen.util.ShapeUtil;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public final class StructureSerializer implements Writable {
    private final GoCodegenContext ctx;
    private final StructureShape shape;
    private final GoPointableIndex nilIndex;

    public StructureSerializer(GoCodegenContext ctx, StructureShape shape) {
        this.ctx = ctx;
        this.shape = shape;

        this.nilIndex = GoPointableIndex.of(ctx.model());
    }

    @Override
    public void accept(GoWriter writer) {
        writer.addImport(ctx.settings().getModuleName() + "/schemas", "schemas");

        var symbol = ctx.symbolProvider().toSymbol(shape);
        var members = shape.members().stream()
                .sorted(Comparator.comparing(MemberShape::getMemberName))
                .toList();
        writer.addUseImports(SmithyGoDependency.SMITHY);
        writer.openBlock("func (v *$L) Serialize(s smithy.ShapeSerializer) {", "}", symbol.getName(), () -> {
            writer.write("s.WriteMap(schemas.$L)", SchemaGenerator.getSchemaName(shape));
            for (var member : members) {
                var target = ShapeUtil.expectMember(ctx.model(), shape, member.getMemberName());
                var ident = String.format("v.%s", ctx.symbolProvider().toMemberName(member));
                generateSerializeMember(writer, member, target, ident);
            }
            writer.write("s.CloseMap()");
        });
        writer.write("");
    }

    private void generateSerializeMember(GoWriter writer, MemberShape member, Shape target, String ident) {
        var schemaName = "schemas." + SchemaGenerator.getMemberSchemaName(shape, member);
        var ptrSuffix = nilIndex.isNillable(member) ? "Ptr" : "";
        switch (target.getType()) {
            case BYTE ->
                    writer.write("s.WriteInt8$L($L, $L)", ptrSuffix, schemaName, ident);
            case SHORT ->
                    writer.write("s.WriteInt16$L($L, $L)", ptrSuffix, schemaName, ident);
            case INTEGER ->
                    writer.write("s.WriteInt32$L($L, $L)", ptrSuffix, schemaName, ident);
            case LONG ->
                    writer.write("s.WriteInt64$L($L, $L)", ptrSuffix, schemaName, ident);

            case FLOAT ->
                    writer.write("s.WriteFloat32$L($L, $L)", ptrSuffix, schemaName, ident);
            case DOUBLE ->
                    writer.write("s.WriteFloat64$L($L, $L)", ptrSuffix, schemaName, ident);

            case STRING ->
                    writer.write("s.WriteString$L($L, $L)", ptrSuffix, schemaName, ident);
            case ENUM ->
                    writer.write("s.WriteString($L, string($L))", schemaName, ident);
            case INT_ENUM ->
                    writer.write("s.WriteInt32($L, int32($L))", schemaName, ident);
            case BOOLEAN ->
                    writer.write("s.WriteBool$L($L, $L)", ptrSuffix, schemaName, ident);
            case TIMESTAMP ->
                    writer.write("s.WriteTime$L($L, $L)", ptrSuffix, schemaName, ident);
            case BLOB ->
                    writer.write("s.WriteBlob($L, $L)", schemaName, ident);

            case LIST, SET, MAP, UNION ->
                    writer.write("serialize$L(s, $L, $L)", target.getId().getName(), schemaName, ident);
            case STRUCTURE ->
                    writer.write("if ($2L != nil) { s.WriteStruct($1L, $2L) }", schemaName, ident);
            case DOCUMENT -> {
                writer.addUseImports(SmithyGoDependency.SMITHY_DOCUMENT);
                writer.write("s.WriteDocument($L, &smithydocument.Opaque{Value: $L})", schemaName, ident);
            }

            // FUTURE(602)
            case BIG_INTEGER, BIG_DECIMAL -> throw new UnsupportedShapeException(target.getType());

            // invalid in this context
            case MEMBER, SERVICE, RESOURCE, OPERATION -> throw new CodegenException("invalid shape " + target.getType());
        }
    }
}
