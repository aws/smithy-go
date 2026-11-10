package software.amazon.smithy.go.codegen;

import java.util.Optional;
import software.amazon.smithy.go.codegen.util.ShapeUtil;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public final class SchemaGenerator {
    private SchemaGenerator() {
    }

    public static String getSchemaName(Shape shape, ServiceShape service) {
        if (Prelude.isPublicPreludeShape(shape)) {
            return "smithyprelude." + shape.getId().getName();
        }
        var name = modelShapeID(shape).getName(service);
        return ShapeUtil.isExported(shape)
                ? name
                : "_" + name;
    }

    public static String getMemberSchemaName(Shape shape, MemberShape member, ServiceShape service) {
        return String.format("%s_%s", getSchemaName(shape, service), member.getMemberName());
    }

    // modelShapeID returns the original model shape ID, preferring the
    // Synthetic trait's archetype (if any) so that synthetic clones of Input /
    // Output inline shapes carry the Smithy model's shape ID rather than the
    // Go-codegen synthetic ID.
    static ShapeId modelShapeID(Shape shape) {
        Optional<Synthetic> synth = shape.getTrait(Synthetic.class);
        if (synth.isPresent() && synth.get().getArchetype().isPresent()) {
            return synth.get().getArchetype().get();
        }
        return shape.getId();
    }

    // Returns the schema reference for use outside the schemas package (e.g. in
    // serde generators). Prelude shapes are already package-qualified, local
    // shapes get the "schemas." prefix. Stub synthetics (no archetype) return
    // "nil" since no schema is generated for them.
    public static String getSchemaRef(Shape shape, ServiceShape service) {
        if (CodegenUtils.isStubSynthetic(shape)) {
            return "nil";
        }
        if (Prelude.isPublicPreludeShape(shape)) {
            return getSchemaName(shape, service);
        }
        return "schemas." + getSchemaName(shape, service);
    }

    public static String getMemberSchemaRef(Shape shape, MemberShape member, ServiceShape service) {
        if (Prelude.isPublicPreludeShape(shape)) {
            return getSchemaName(shape, service) + "_" + member.getMemberName();
        }
        return "schemas." + getMemberSchemaName(shape, member, service);
    }
}
