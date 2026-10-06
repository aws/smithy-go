package software.amazon.smithy.go.codegen.serde2;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SymbolUtils.buildPackageSymbol;
import static software.amazon.smithy.go.codegen.SymbolUtils.pointerTo;

import java.util.LinkedHashMap;
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
        var fields = new LinkedHashMap<String, Symbol>();
        fields.put("options", pointerTo(buildPackageSymbol("Options")));
        fields.put("operationSchema", SmithyGoDependency.SMITHY.pointableSymbol("OperationSchema"));
        fields.put("output", SmithyGoDependency.SMITHY.interfaceSymbol("Deserializable"));
        return fields;
    }

    @Override
    public Writable getFuncBody() {
        return goTemplate("""
                out, md, err := next.HandleDeserialize(ctx, in)

                resp, ok := out.RawResponse.(*smithyhttp.Response)
                if !ok {
                    if err != nil {
                        // Transport-level failure with no HTTP response to close.
                        return out, md, err
                    }
                    return out, md, &smithy.DeserializationError{Err: fmt.Errorf("unknown transport type %T", out.RawResponse)}
                }

                // Close the response body on return. CloseResponseBody keeps the body
                // open only for a successful caller-owned stream (a streaming payload
                // with a nil error); on any error -- including one surfaced by an
                // interceptor that runs after OperationDeserializer (after transmit or
                // before deserialization) -- it closes the received body. Registering
                // this before the error check below is what covers those interceptor
                // aborts. Event streams close their own body in the event stream
                // deserializer.
                if !m.operationSchema.IsInputEventStream() && !m.operationSchema.IsOutputEventStream() {
                    _, isStreamingPayload := m.output.(smithy.StreamingOutput)
                    defer func() {
                        smithyhttp.CloseResponseBody(ctx, resp, isStreamingPayload, err)
                    }()
                }

                if err != nil {
                    return out, md, err
                }

                _, span := tracing.StartSpan(ctx, "OperationDeserializer")
                endTimer := startMetricTimer(ctx, "client.call.deserialization_duration")

                err = m.options.Protocol.DeserializeResponse(ctx, m.operationSchema, TypeRegistry, resp, m.output)
                out.Result = m.output

                endTimer()
                span.End()

                return out, md, err
                """);
    }
}
