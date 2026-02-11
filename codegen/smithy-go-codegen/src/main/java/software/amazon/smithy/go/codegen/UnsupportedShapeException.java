package software.amazon.smithy.go.codegen;

import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.model.shapes.ShapeType;

public class UnsupportedShapeException extends CodegenException {
    public UnsupportedShapeException(ShapeType type) {
        super("unsupported shape type: " + type);
    }
}
