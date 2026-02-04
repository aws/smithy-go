package software.amazon.smithy.go.codegen;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SymbolUtils.buildPackageSymbol;
import static software.amazon.smithy.go.codegen.SymbolUtils.pointerTo;

import java.util.Map;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.middleware.DeserializeStepMiddleware;

public class Serde2DeserializeResponseMiddleware extends DeserializeStepMiddleware {
    @Override
    public String getStructName() {
        return "deserializeResponseMiddleware";
    }

    @Override
    public String getId() {
        return "OperationDeserializer";
    }

    @Override
    public Map<String, Symbol> getFields() {
        return Map.of(
                "options", pointerTo(buildPackageSymbol("Options"))
        );
    }

    @Override
    public Writable getFuncBody() {
        return goTemplate("""
                return next.HandleDeserialize(ctx, in)
                """);
    }
}
