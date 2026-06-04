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
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.RequiredTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
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
        if (Prelude.isPublicPreludeShape(shape)) {
            writer.addUseImports(SmithyGoDependency.SMITHY_PRELUDE);
        }

        var symbol = ctx.symbolProvider().toSymbol(shape);
        var members = shape.members().stream()
                .filter(m -> !StreamingTrait.isEventStream(ctx.model(), m))
                .filter(m -> !ctx.model().expectShape(m.getTarget()).hasTrait(StreamingTrait.class))
                .sorted(Comparator.comparing(MemberShape::getMemberName))
                .toList();
        writer.addUseImports(SmithyGoDependency.SMITHY);
        var schemaRef = SchemaGenerator.getSchemaRef(shape, ctx.service());
        writer.openBlock("func (v *$L) Serialize(s smithy.ShapeSerializer) {", "}", symbol.getName(), () -> {
            writer.write("s.WriteStruct($L)", schemaRef);
            writer.write("v.SerializeMembers(s)");
            writer.write("s.CloseStruct()");
        });
        writer.write("");
        writer.openBlock("func (v *$L) SerializeMembers(s smithy.ShapeSerializer) {", "}", symbol.getName(), () -> {
            for (var member : members) {
                var target = ShapeUtil.expectMember(ctx.model(), shape, member.getMemberName());
                var ident = String.format("v.%s", ctx.symbolProvider().toMemberName(member));
                generateSerializeMember(writer, member, target, ident);
            }
        });
        writer.write("");
    }

    private void generateSerializeMember(GoWriter writer, MemberShape member, Shape target, String ident) {
        var schemaName = SchemaGenerator.getMemberSchemaRef(shape, member, ctx.service());
        var isNillable = nilIndex.isNillable(member);
        var isRequired = member.hasTrait(RequiredTrait.class);
        switch (target.getType()) {
            case BYTE ->
                    writeScalar(writer, isNillable, isRequired, ident, "0", "WriteInt8", schemaName);
            case SHORT ->
                    writeScalar(writer, isNillable, isRequired, ident, "0", "WriteInt16", schemaName);
            case INTEGER ->
                    writeScalar(writer, isNillable, isRequired, ident, "0", "WriteInt32", schemaName);
            case LONG ->
                    writeScalar(writer, isNillable, isRequired, ident, "0", "WriteInt64", schemaName);

            case FLOAT ->
                    writeScalar(writer, isNillable, isRequired, ident, "0", "WriteFloat32", schemaName);
            case DOUBLE ->
                    writeScalar(writer, isNillable, isRequired, ident, "0", "WriteFloat64", schemaName);

            case STRING, ENUM -> {
                if (ShapeUtil.isEnum(target)) {
                    writer.write("if $1L != \"\" { s.WriteString($2L, string($1L)) }", ident, schemaName);
                } else {
                    writeScalar(writer, isNillable, isRequired, ident, "\"\"", "WriteString", schemaName);
                }
            }
            case INT_ENUM ->
                    writer.write("if $1L != 0 { s.WriteInt32($2L, int32($1L)) }", ident, schemaName);
            case BOOLEAN ->
                    writeScalar(writer, isNillable, isRequired, ident, "false", "WriteBool", schemaName);
            case TIMESTAMP ->
                    writeScalar(writer, isNillable, isRequired, ident, "", "WriteTime", schemaName);
            case BLOB ->
                    writer.write("if $2L != nil { s.WriteBlob($1L, $2L) }", schemaName, ident);

            case LIST, SET, MAP, UNION ->
                    writer.write("serialize$L(s, $L, $L)", target.getId().getName(), schemaName, ident);
            case STRUCTURE ->
                    writer.write("if $2L != nil { s.WriteStruct($1L)\n$2L.SerializeMembers(s)\ns.CloseStruct() }", schemaName, ident);
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

    // Generates a nil-guarded (pointer) or zero-guarded (value) scalar write.
    // writeMethod is e.g. "WriteInt32". schemaName is the schema ref. ident is
    // the Go expression for the member value (e.g. "v.Foo").
    private void writeScalar(GoWriter writer, boolean isNillable, boolean isRequired, String ident, String zeroValue,
                             String writeMethod, String schemaName) {
        if (isNillable) {
            writer.write("if $1L != nil { s.$3L($2L, *$1L) }", ident, schemaName, writeMethod);
        } else if (isRequired) {
            writer.write("s.$3L($2L, $1L)", ident, schemaName, writeMethod);
        } else {
            if (zeroValue.isEmpty()) {
                writer.write("if !$1L.IsZero() { s.$3L($2L, $1L) }", ident, schemaName, writeMethod);
            } else {
                writer.write("if $1L != " + zeroValue + " { s.$3L($2L, $1L) }", ident, schemaName, writeMethod);
            }
        }
    }
}
