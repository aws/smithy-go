package software.amazon.smithy.go.codegen.serde2;

import java.util.Map;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.model.shapes.MapShape;

public class MapDeserializer implements Writable {
    private final GoCodegenContext ctx;
    private final MapShape shape;

    public MapDeserializer(GoCodegenContext ctx, MapShape shape) {
        this.ctx = ctx;
        this.shape = shape;
    }

    @Override
    public void accept(GoWriter writer) {
        writer.addUseImports(SmithyGoDependency.SMITHY);
        writer.writeGoTemplate("""
                func deserialize$shapeName:L(d smithy.ShapeDeserializer, s *smithy.Schema, v *$symbol:T) error {
                    return smithy.ReadMap(d, s, func(k string) error {
                        _ = k
                        return nil
                    })
                }
                """, Map.of(
                "shapeName", shape.getId().getName(),
                "symbol", ctx.symbolProvider().toSymbol(shape)
        ));
    }
}
