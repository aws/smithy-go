package software.amazon.smithy.go.codegen.serde2;

import java.util.Comparator;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.ProtocolDocumentGenerator;
import software.amazon.smithy.go.codegen.SchemaGenerator;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.UnsupportedShapeException;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.knowledge.GoPointableIndex;
import software.amazon.smithy.go.codegen.util.ShapeUtil;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.StreamingTrait;

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
                .filter(m -> !StreamingTrait.isEventStream(ctx.model(), m))
                .filter(m -> !ctx.model().expectShape(m.getTarget()).hasTrait(StreamingTrait.class))
                .sorted(Comparator.comparing(MemberShape::getMemberName))
                .toList();
        writer.openBlock("func (v *$L) Deserialize(d smithy.ShapeDeserializer) error {", "}", symbol.getName(), () -> {
            writer.openBlock("return smithy.ReadStruct(d, $L, func(s *smithy.Schema) error {", "})", SchemaGenerator.getSchemaRef(shape, ctx.service()), () -> {
                writer.openBlock("switch s {", "}", () -> {
                    for (var member : members) {
                        writer.write("case $L:", SchemaGenerator.getMemberSchemaRef(shape, member, ctx.service()));
                        renderMember(writer, member, ctx.model().expectShape(member.getTarget()), "v." + ctx.symbolProvider().toMemberName(member));
                    }
                });
                writer.write("return nil");
            });
        });
    }

    private void renderMember(GoWriter writer, MemberShape member, Shape target, String ident) {
        var schemaName = SchemaGenerator.getMemberSchemaRef(shape, member, ctx.service());
        var ptrSuffix = nilIndex.isNillable(member) ? "Ptr" : "";
        switch (target.getType()) {
            case BYTE ->
                    writer.write("return d.ReadInt8$L($L, &$L)", ptrSuffix, schemaName, ident);
            case SHORT ->
                    writer.write("return d.ReadInt16$L($L, &$L)", ptrSuffix, schemaName, ident);
            case INTEGER ->
                    writer.write("return d.ReadInt32$L($L, &$L)", ptrSuffix, schemaName, ident);
            case LONG ->
                    writer.write("return d.ReadInt64$L($L, &$L)", ptrSuffix, schemaName, ident);

            case FLOAT ->
                    writer.write("return d.ReadFloat32$L($L, &$L)", ptrSuffix, schemaName, ident);
            case DOUBLE ->
                    writer.write("return d.ReadFloat64$L($L, &$L)", ptrSuffix, schemaName, ident);

            case STRING, ENUM -> {
                    if (ShapeUtil.isEnum(target)) {
                        writer.write("""
                                var ev string
                                if err := d.ReadString($1L, &ev); err != nil {
                                    return err
                                }
                                $2L = $3T(ev)
                                return nil""", schemaName, ident, ctx.symbolProvider().toSymbol(target));
                    } else {
                        writer.write("return d.ReadString$L($L, &$L)", ptrSuffix, schemaName, ident);
                    }
            }
            case INT_ENUM ->
                    writer.write("""
                            var ev int32
                            if err := d.ReadInt32($1L, &ev); err != nil {
                                return err
                            }
                            $2L = $3T(ev)
                            return nil""", schemaName, ident, ctx.symbolProvider().toSymbol(target));
            case BOOLEAN ->
                    writer.write("return d.ReadBool$L($L, &$L)", ptrSuffix, schemaName, ident);
            case TIMESTAMP ->
                    writer.write("return d.ReadTime$L($L, &$L)", ptrSuffix, schemaName, ident);
            case BLOB ->
                    writer.write("return d.ReadBlob($L, &$L)", schemaName, ident);

            case LIST, SET, MAP, UNION ->
                    writer.write("return deserialize$L(d, $L, &$L)", target.getId().getName(), schemaName, ident);
            case STRUCTURE -> {
                if (nilIndex.isNillable(member)) {
                    writer.write("""
                            $1L = &$2T{}
                            return $1L.Deserialize(d)""", ident, ctx.symbolProvider().toSymbol(target));
                } else {
                    writer.write("return $L.Deserialize(d)", ident);
                }
            }
            case DOCUMENT -> {
                var unmarshaler = ProtocolDocumentGenerator.Utilities.getInternalDocumentSymbolBuilder(
                        ctx.settings(), ProtocolDocumentGenerator.INTERNAL_NEW_DOCUMENT_UNMARSHALER_FUNC).build();
                writer.addUseImports(SmithyGoDependency.SMITHY_DOCUMENT);
                writer.write("""
                        var dv smithydocument.Value
                        if err := d.ReadDocument($L, &dv); err != nil {
                            return err
                        }
                        if ov, ok := dv.(smithydocument.Opaque); ok {
                            $L = $T(ov.Value)
                        }
                        return nil""", schemaName, ident, unmarshaler);
            }

            // FUTURE(602)
            case BIG_INTEGER, BIG_DECIMAL -> throw new UnsupportedShapeException(target.getType());

            // invalid in this context
            case MEMBER, SERVICE, RESOURCE, OPERATION -> throw new UnsupportedShapeException(target.getType());
        }
    }
}
