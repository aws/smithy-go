package software.amazon.smithy.go.codegen.serde2;

import java.util.Comparator;
import java.util.List;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SchemaGenerator;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.util.ShapeUtil;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
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

        writer.openBlock("func serialize$L(s smithy.ShapeSerializer, schema *smithy.Schema, v $T) {", "}",
                shape.getId().getName(), symbol, () -> {
            writer.openBlock("switch vv := v.(type) {", "}", () -> {
                renderCases(writer, members);
            });
        });
    }

    private void renderCases(GoWriter writer, List<MemberShape> members) {
        for (var member : members) {
            var variantSymbol = Symbol.builder()
                    .name(ctx.symbolProvider().toMemberName(member))
                    .namespace(ctx.settings().getModuleName() + "/types", ".")
                    .build();
            var variantSchema = SchemaGenerator.getMemberSchemaRef(shape, member, ctx.service());
            var target = ctx.model().expectShape(member.getTarget());

            writer.write("case *$T:", variantSymbol);
            writer.indent();
            writer.write("s.WriteUnion(schema, $L)", variantSchema);
            writeVariantValue(writer, target, variantSchema);
            writer.write("s.CloseUnion()");
            writer.dedent();
        }
    }

    private void writeVariantValue(GoWriter writer, Shape target, String schemaName) {
        switch (target.getType()) {
            case BYTE -> writer.write("s.WriteInt8($L, vv.Value)", schemaName);
            case SHORT -> writer.write("s.WriteInt16($L, vv.Value)", schemaName);
            case INTEGER -> writer.write("s.WriteInt32($L, vv.Value)", schemaName);
            case LONG -> writer.write("s.WriteInt64($L, vv.Value)", schemaName);
            case FLOAT -> writer.write("s.WriteFloat32($L, vv.Value)", schemaName);
            case DOUBLE -> writer.write("s.WriteFloat64($L, vv.Value)", schemaName);
            case BOOLEAN -> writer.write("s.WriteBool($L, vv.Value)", schemaName);
            case STRING, ENUM -> {
                if (ShapeUtil.isEnum(target)) {
                    writer.write("s.WriteString($L, string(vv.Value))", schemaName);
                } else {
                    writer.write("s.WriteString($L, vv.Value)", schemaName);
                }
            }
            case BLOB -> writer.write("s.WriteBlob($L, vv.Value)", schemaName);
            case TIMESTAMP -> writer.write("s.WriteTime($L, vv.Value)", schemaName);
            case INT_ENUM -> writer.write("s.WriteInt32($L, int32(vv.Value))", schemaName);
            case BIG_INTEGER -> writer.write("s.WriteBigInt($L, vv.Value)", schemaName);
            case BIG_DECIMAL -> writer.write("s.WriteBigFloat($L, vv.Value)", schemaName);
            case STRUCTURE -> {
                writer.write("s.WriteStruct($L)", schemaName);
                writer.write("vv.Value.SerializeMembers(s)");
                writer.write("s.CloseStruct()");
            }
            case LIST, SET, MAP -> writer.write("serialize$L(s, $L, vv.Value)", target.getId().getName(), schemaName);
            case UNION -> writer.write("serialize$L(s, $L, vv.Value)", target.getId().getName(), schemaName);
            case DOCUMENT -> {
                writer.addUseImports(SmithyGoDependency.SMITHY_DOCUMENT);
                writer.write("s.WriteDocument($L, &smithydocument.Opaque{Value: vv.Value})", schemaName);
            }
            case MEMBER, SERVICE, RESOURCE, OPERATION -> throw new CodegenException("invalid shape type " + target.getType());
        }
    }
}
