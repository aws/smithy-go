package software.amazon.smithy.go.codegen.serde2;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SymbolUtils.buildPackageSymbol;
import static software.amazon.smithy.go.codegen.SymbolUtils.pointerTo;

import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.middleware.SerializeStepMiddleware;

public class Serde2SerializeRequestMiddleware extends SerializeStepMiddleware {
    @Override
    public String getStructName() {
        return "serializeRequestMiddleware";
    }

    @Override
    public String getId() {
        return "OperationSerializer";
    }

    @Override
    public Map<String, Symbol> getFields() {
        var fields = new LinkedHashMap<String, Symbol>();
        fields.put("options", pointerTo(buildPackageSymbol("Options")));
        fields.put("operationSchema", SmithyGoDependency.SMITHY.pointableSymbol("OperationSchema"));
        return fields;
    }

    @Override
    public Writable getFuncBody() {
        return GoWriter.goTemplate("""
                $D
                req, ok := in.Request.(*smithyhttp.Request)
                if !ok {
                    return middleware.SerializeOutput{}, middleware.Metadata{}, fmt.Errorf("unexpected transport type %T", in.Request)
                }

                input, ok := in.Parameters.(smithy.Serializable)
                if !ok {
                    return middleware.SerializeOutput{}, middleware.Metadata{}, fmt.Errorf("input %T is not Serializable", in.Request)
                }

                _, span := tracing.StartSpan(ctx, "OperationSerializer")
                endTimer := startMetricTimer(ctx, "client.call.serialization_duration")

                err := m.options.Protocol.SerializeRequest(ctx, m.operationSchema, input, req)

                endTimer()
                span.End()

                if err != nil {
                    return middleware.SerializeOutput{}, middleware.Metadata{}, err
                }

                // Compute the request content length now that the body is serialized,
                // unless the input is a caller-owned event stream.
                if !m.operationSchema.IsInputEventStream() {
                    if err := smithyhttp.ComputeRequestContentLength(req); err != nil {
                        return middleware.SerializeOutput{}, middleware.Metadata{}, fmt.Errorf("compute content length: %w", err)
                    }
                }

                return next.HandleSerialize(ctx, in)
                """, SmithyGoDependency.SMITHY_TRACING);
    }
}
