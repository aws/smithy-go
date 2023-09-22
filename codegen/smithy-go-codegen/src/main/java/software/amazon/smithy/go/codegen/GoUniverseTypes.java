package software.amazon.smithy.go.codegen;

import software.amazon.smithy.codegen.core.Symbol;

/**
 * Collection of Symbol constants for golang universe types.
 * See <a href="https://go.dev/ref/spec#Predeclared_identifiers">predeclared identifiers</a>.
 */
public class GoUniverseTypes {
    public static Symbol Any = universe("any");
    public static Symbol Bool = universe("bool");
    public static Symbol Byte = universe("byte");
    public static Symbol Comparable = universe("comparable");

    public static Symbol Complex64 = universe("complex64");
    public static Symbol Complex128 = universe("complex128");
    public static Symbol Error = universe("error");
    public static Symbol Float32 = universe("float32");
    public static Symbol Float64 = universe("float64");

    public static Symbol Int = universe("int");
    public static Symbol Int8 = universe("int8");
    public static Symbol Int16 = universe("int16");
    public static Symbol Int32 = universe("int32");
    public static Symbol Int64 = universe("int64");
    public static Symbol Rune = universe("rune");
    public static Symbol String = universe("string");

    public static Symbol Uint = universe("uint");
    public static Symbol Uint8 = universe("uint8");
    public static Symbol Uint16 = universe("uint16");
    public static Symbol Uint32 = universe("uint32");
    public static Symbol Uint64 = universe("uint64");
    public static Symbol Uintptr = universe("uintptr");

    private static Symbol universe(String name) {
        return SymbolUtils.createValueSymbolBuilder(name).putProperty(SymbolUtils.GO_UNIVERSE_TYPE, true).build();
    }
}

