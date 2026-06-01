package software.amazon.smithy.go.codegen;

import software.amazon.smithy.model.traits.Trait;

public interface TraitGenerator {
    Writable render(Trait trait);
}
