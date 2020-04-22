package software.amazon.smithy.go.codegen;

import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolReference;
import software.amazon.smithy.model.shapes.Shape;

public class SymbolUtils {
    private SymbolUtils() {
    }

    public static Symbol.Builder createValueSymbolBuilder(String typeName) {
        return Symbol.builder()
                .putProperty("pointable", false)
                .name(typeName);
    }

    public static Symbol.Builder createPointableSymbolBuilder(String typeName) {
        return Symbol.builder()
                .putProperty("pointable", true)
                .name(typeName);
    }

    public static Symbol.Builder createValueSymbolBuilder(Shape shape, String typeName) {
        return createValueSymbolBuilder(typeName).putProperty("shape", shape);
    }

    public static Symbol.Builder createPointableSymbolBuilder(Shape shape, String typeName) {
        return createPointableSymbolBuilder(typeName).putProperty("shape", shape);
    }

    public static Symbol.Builder createPointableSymbolBuilder(String typeName, String namespace) {
        return createPointableSymbolBuilder(typeName).namespace(namespace, ".");
    }

    public static Symbol.Builder createValueSymbolBuilder(String typeName, String namespace) {
        return createValueSymbolBuilder(typeName).namespace(namespace, ".");
    }

    public static Symbol.Builder createPointableSymbolBuilder(Shape shape, String typeName, String namespace) {
        return createPointableSymbolBuilder(shape, typeName).namespace(namespace, ".");
    }

    public static Symbol.Builder createValueSymbolBuilder(Shape shape, String typeName, String namespace) {
        return createValueSymbolBuilder(shape, typeName).namespace(namespace, ".");
    }

    public static SymbolReference createNamespaceReference(GoDependency dependency) {
        String namespace = dependency.importPath;
        return createNamespaceReference(dependency, CodegenUtils.getDefaultPackageImportName(namespace));
    }

    public static SymbolReference createNamespaceReference(GoDependency dependency, String alias) {
        // Go generally imports an entire package under a single name, which defaults to the last
        // part of the package name path. So we need to create a symbol for that namespace to reference.
        String namespace = dependency.importPath;
        Symbol namespaceSymbol = Symbol.builder()
                // We're not referencing a particular symbol from the namespace, so we leave the name blank.
                .name("")
                .putProperty("namespaceSymbol", true)
                .namespace(namespace, "/")
                .addDependency(dependency)
                .build();
        return SymbolReference.builder()
                .symbol(namespaceSymbol)
                .alias(alias)
                .build();
    }
}
