package software.amazon.smithy.go.codegen.endpoints;

import software.amazon.smithy.codegen.core.Symbol;

public interface FnProvider {
    Symbol fnFor(String name);
}
