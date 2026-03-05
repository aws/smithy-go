package software.amazon.smithy.go.codegen.serde2;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SymbolUtils.buildPackageSymbol;
import static software.amazon.smithy.go.codegen.SymbolUtils.pointerTo;

import java.util.Map;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.Writable;
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
                "options", pointerTo(buildPackageSymbol("Options")),
                "output", SmithyGoDependency.SMITHY.interfaceSymbol("Deserializable")
        );
    }

    @Override
    public Writable getFuncBody() {
        return goTemplate("""
                out, md, err := next.HandleDeserialize(ctx, in)
                if err != nil {
                    return out, md, err
                }

                resp, ok := out.RawResponse.(*smithyhttp.Response)
                if !ok {
                    return out, md, &smithy.DeserializationError{Err: fmt.Errorf("unknown transport type %T", out.RawResponse)}
                }

                _, span := tracing.StartSpan(ctx, "OperationDeserializer")
                endTimer := startMetricTimer(ctx, "client.call.deserialization_duration")

                err = m.options.Protocol.DeserializeResponse(ctx, TypeRegistry, resp, m.output)
                out.Result = m.output

                endTimer()
                span.End()

                return out, md, err
                """);
    }
}
