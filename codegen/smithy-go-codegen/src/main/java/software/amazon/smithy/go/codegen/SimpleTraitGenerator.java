package software.amazon.smithy.go.codegen;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.model.traits.Trait;

class SimpleTraitGenerator<T extends Trait> implements TraitGenerator {
    private final Symbol symbol;
    private final Map<String, Function<T, Object>> mappings = new LinkedHashMap<>();

    public SimpleTraitGenerator(Symbol symbol) {
        this.symbol = symbol;
    }

    public SimpleTraitGenerator(Symbol symbol,
                                String k1, Function<T, Object> v1) {
        this.symbol = symbol;

        mappings.put(k1, v1);
    }

    public SimpleTraitGenerator(Symbol symbol,
                                String k1, Function<T, Object> v1,
                                String k2, Function<T, Object> v2) {
        this.symbol = symbol;

        mappings.put(k1, v1);
        mappings.put(k2, v2);
    }

    @Override
    public Writable render(Trait trait) {
        return goTemplate("&$T{$W}", symbol, Writable.map(mappings.entrySet(), entry -> {
            var value = entry.getValue().apply((T) trait);
            return goTemplate("$L: $L,", entry.getKey(), formatValue(value));
        }));
    }

    private String formatValue(Object value) {
        return value instanceof String
                ? "\"" + value + "\""
                : value.toString();
    }
}
