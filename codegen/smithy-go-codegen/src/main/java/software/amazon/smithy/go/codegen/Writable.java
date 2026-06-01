package software.amazon.smithy.go.codegen;

import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Function;

public interface Writable extends Consumer<GoWriter> {
    static <T> Writable map(Collection<T> items, Function<T, Writable> mapper, boolean writeNewlines) {
        return ChainWritable.of(items.stream()
                .map(mapper)
                .toList()
        ).compose(writeNewlines);
    }

    static <T> Writable map(Collection<T> items, Function<T, Writable> mapper) {
        return map(items, mapper, false);
    }
}
