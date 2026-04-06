package software.amazon.smithy.go.codegen.serde2;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SymbolUtils.buildPackageSymbol;
import static software.amazon.smithy.go.codegen.SymbolUtils.pointerTo;

import java.util.Map;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.EventStreamGenerator;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.SchemaGenerator;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.middleware.DeserializeStepMiddleware;
import software.amazon.smithy.model.knowledge.EventStreamIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.utils.MapUtils;

public class Serde2EventStreamMiddleware extends DeserializeStepMiddleware {
    private final GoCodegenContext ctx;
    private final OperationShape operation;

    public Serde2EventStreamMiddleware(GoCodegenContext ctx, OperationShape operation) {
        this.ctx = ctx;
        this.operation = operation;
    }

    @Override
    public String getStructName() {
        return String.format("deserializeOpEventStream%s",
                operation.getId().getName(ctx.service()));
    }

    @Override
    public String getId() {
        return "OperationEventStreamDeserializer";
    }

    @Override
    public Map<String, Symbol> getFields() {
        return Map.of(
                "options", pointerTo(buildPackageSymbol("Options"))
        );
    }

    @Override
    public Writable getFuncBody() {
        var model = ctx.model();
        var service = ctx.service();
        var symbolProvider = ctx.symbolProvider();
        var streamIndex = EventStreamIndex.of(model);

        var inputInfo = streamIndex.getInputInfo(operation);
        var outputInfo = streamIndex.getOutputInfo(operation);

        var outputShape = model.expectShape(operation.getOutputShape());
        var outputSymbol = symbolProvider.toSymbol(outputShape);

        var opEventStreamConstructor = EventStreamGenerator
                .getEventStreamOperationStructureConstructor(service, operation);
        var opEventStreamSymbol = EventStreamGenerator
                .getEventStreamOperationStructureSymbol(service, operation);

        var isV2 = EventStreamGenerator.isV2EventStream(model, operation);

        if (isV2) {
            return v2FuncBody(inputInfo, outputInfo, outputSymbol, opEventStreamConstructor, opEventStreamSymbol);
        }

        return goTemplate("""
                out, md, err := next.HandleDeserialize(ctx, in)
                if err != nil {
                    return out, md, err
                }

                $respDecl:L, ok := out.RawResponse.($smithyhttpResponse:P)
                if !ok {
                    return out, md, $fmtErrorf:T("unknown transport type: %T", out.RawResponse)
                }

                output, ok := out.Result.($output:P)
                if out.Result != nil && !ok {
                    return out, md, $fmtErrorf:T("unexpected output result type %T, expected $output:P", out.Result)
                } else if out.Result == nil {
                    output = &$output:T{}
                    out.Result = output
                }

                $writerSetup:W
                $initialRequest:W
                $readerSetup:W
                $initialResponse:W

                output.eventStream = $esConstructor:T(func(stream $esStruct:P) {
                    $wireWriter:W
                    $wireReader:W
                })

                go output.eventStream.waitStreamClose()

                return out, md, nil
                """,
                Map.ofEntries(
                        Map.entry("smithyhttpResponse",
                            SmithyGoDependency.SMITHY_HTTP_TRANSPORT.pointableSymbol("Response")),
                        Map.entry("fmtErrorf", SmithyGoDependency.FMT.func("Errorf")),
                        Map.entry("output", outputSymbol),
                        Map.entry("respDecl", outputInfo.isPresent() ? "resp" : "_"),
                        Map.entry("esConstructor", opEventStreamConstructor),
                        Map.entry("esStruct", opEventStreamSymbol),
                        Map.entry("writerSetup", inputInfo.isPresent()
                                ? writerSetup(inputInfo.get().getEventStreamTarget().asUnionShape().get())
                                : (Writable) w -> {}),
                        Map.entry("initialRequest", inputInfo.isPresent()
                                ? initialRequest()
                                : (Writable) w -> {}),
                        Map.entry("readerSetup", outputInfo.isPresent()
                                ? readerSetup(outputInfo.get().getEventStreamTarget().asUnionShape().get())
                                : (Writable) w -> {}),
                        Map.entry("initialResponse", outputInfo.isPresent()
                                ? initialResponse(outputShape)
                                : (Writable) w -> {}),
                        Map.entry("wireWriter", inputInfo.isPresent()
                                ? (Writable) w -> w.write("stream.Writer = eventWriter")
                                : (Writable) w -> {}),
                        Map.entry("wireReader", outputInfo.isPresent()
                                ? (Writable) w -> w.write("stream.Reader = eventReader")
                                : (Writable) w -> {})
                ));
    }

    private Writable writerSetup(UnionShape inputEventStream) {
        var service = ctx.service();
        var schemaName = "schemas." + SchemaGenerator.getSchemaName(inputEventStream, service);
        var adapterName = EventStreamGenerator.getEventStreamWriterAdapterName(service, inputEventStream);

        return goTemplate("""
                inputStreamWriter := $getInputStreamWriter:T(ctx)
                if inputStreamWriter == nil {
                    return out, md, $fmtErrorf:T("input stream writer not found in context")
                }
                if rscheme := getResolvedAuthScheme(ctx); rscheme != nil {
                    if es, ok := rscheme.Scheme.Signer().($eventStreamSigner:T); ok {
                        req, _ := in.Request.($smithyhttpRequest:P)
                        msgSigner, serr := es.NewMessageSigner(ctx, req, getIdentity(ctx), rscheme.SignerProperties)
                        if serr != nil {
                            return out, md, $fmtErrorf:T("event stream signer: %w", serr)
                        }
                        inputStreamWriter = $newSigningWriter:T(inputStreamWriter, msgSigner)
                    }
                }
                eventWriter := &$adapter:L{
                    writer: $newWriter:T(m.options.Protocol, $schema:L, inputStreamWriter),
                }
                defer func() {
                    if err != nil {
                        _ = eventWriter.Close()
                    }
                }()
                """,
                MapUtils.of(
                        "getInputStreamWriter",
                            SmithyGoDependency.SMITHY_HTTP_TRANSPORT.func("GetInputStreamWriter"),
                        "fmtErrorf", SmithyGoDependency.FMT.func("Errorf"),
                        "adapter", adapterName,
                        "newWriter",
                            SmithyGoDependency.SMITHY_HTTP_TRANSPORT.func("NewEventStreamWriter"),
                        "eventStreamSigner",
                            SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("EventStreamSigner"),
                        "smithyhttpRequest",
                            SmithyGoDependency.SMITHY_HTTP_TRANSPORT.pointableSymbol("Request"),
                        "newSigningWriter",
                            SmithyGoDependency.SMITHY_EVENTSTREAM.func("NewSigningWriter"),
                        "schema", schemaName
                ));
    }

    private Writable readerSetup(UnionShape outputEventStream) {
        var service = ctx.service();
        var schemaName = "schemas." + SchemaGenerator.getSchemaName(outputEventStream, service);
        var adapterConstructor = EventStreamGenerator
                .getEventStreamReaderAdapterConstructor(service, outputEventStream);

        return goTemplate("""
                eventReader := $newAdapter:L(
                    $newReader:T(m.options.Protocol, $schema:L, TypeRegistry, resp.Body),
                )
                defer func() {
                    if err != nil {
                        _ = eventReader.Close()
                    }
                }()
                """,
                MapUtils.of(
                        "newAdapter", adapterConstructor,
                        "newReader",
                            SmithyGoDependency.SMITHY_HTTP_TRANSPORT.func("NewEventStreamReader"),
                        "schema", schemaName
                ));
    }

    private Writable initialRequest() {
        var inputShape = ctx.model().expectShape(operation.getInputShape());
        var inputSchemaName = "schemas." + SchemaGenerator.getSchemaName(inputShape, ctx.service());

        return goTemplate("""
                if m.options.Protocol.HasInitialEventMessage() {
                    opInput, ok := getOperationInput(ctx).($serializable:T)
                    if !ok {
                        return out, md, $fmtErrorf:T("operation input is not serializable")
                    }
                    if err = m.options.Protocol.SerializeInitialRequest($inputSchema:L, opInput, inputStreamWriter); err != nil {
                        return out, md, $fmtErrorf:T("serialize initial request: %w", err)
                    }
                }
                """,
                MapUtils.of(
                        "serializable", SmithyGoDependency.SMITHY.valueSymbol("Serializable"),
                        "fmtErrorf", SmithyGoDependency.FMT.func("Errorf"),
                        "inputSchema", inputSchemaName
                ));
    }

    private Writable initialResponse(software.amazon.smithy.model.shapes.Shape outputShape) {
        var outputSchemaName = "schemas." + SchemaGenerator.getSchemaName(outputShape, ctx.service());

        return goTemplate("""
                if m.options.Protocol.HasInitialEventMessage() {
                    if err = m.options.Protocol.DeserializeInitialResponse($outputSchema:L, resp.Body, output); err != nil {
                        return out, md, $fmtErrorf:T("deserialize initial response: %w", err)
                    }
                }
                """,
                MapUtils.of(
                        "fmtErrorf", SmithyGoDependency.FMT.func("Errorf"),
                        "outputSchema", outputSchemaName
                ));
    }

    private Writable v2FuncBody(
            java.util.Optional<software.amazon.smithy.model.knowledge.EventStreamInfo> inputInfo,
            java.util.Optional<software.amazon.smithy.model.knowledge.EventStreamInfo> outputInfo,
            Symbol outputSymbol,
            Symbol opEventStreamConstructor,
            Symbol opEventStreamSymbol) {

        // For v2 (early-return) event streams, we must:
        // 1. Set up the writer (from the pipe in context)
        // 2. Set up the reader using an async pipe (not the response body directly)
        // 3. Wire up the event stream and send PartialResult to unblock the caller
        // 4. THEN call next.HandleDeserialize (sends the HTTP request)
        // 5. Pipe the response body into the async reader
        // 6. Add output to metadata for the Build-step middleware
        return goTemplate("""
                var out $deserializeOutput:T
                var md $metadata:T
                var err error

                output := &$output:T{}
                output.initialReply = make(chan $initialReply:T, 1)

                $writerSetup:W
                $readerSetup:W

                output.eventStream = $esConstructor:T(func(stream $esStruct:P) {
                    $wireWriter:W
                    $wireReader:W
                })

                go output.eventStream.waitStreamClose()

                prc, _ := ctx.Value(partialResultChan{}).(chan PartialResult)
                if prc != nil {
                    select {
                    case <-prc:
                    default:
                    }
                    prc <- PartialResult{
                        Output:   output,
                        Metadata: $newMetadata:T{},
                    }
                }

                out, md, err = next.HandleDeserialize(ctx, in)
                if err != nil {
                    $asyncError:W
                    return out, md, err
                }

                resp, ok := out.RawResponse.($smithyhttpResponse:P)
                if !ok {
                    return out, md, $fmtErrorf:T("unknown transport type: %T", out.RawResponse)
                }

                $asyncPipe:W
                $addToMetadata:T(&md, output)
                return out, md, nil
                """,
                Map.ofEntries(
                        Map.entry("output", outputSymbol),
                        Map.entry("deserializeOutput",
                            SmithyGoDependency.SMITHY_MIDDLEWARE.valueSymbol("DeserializeOutput")),
                        Map.entry("metadata",
                            SmithyGoDependency.SMITHY_MIDDLEWARE.valueSymbol("Metadata")),
                        Map.entry("initialReply",
                            EventStreamGenerator.getEventStreamInitialReplyStructureSymbol(
                                    ctx.service(), operation)),
                        Map.entry("smithyhttpResponse",
                            SmithyGoDependency.SMITHY_HTTP_TRANSPORT.pointableSymbol("Response")),
                        Map.entry("fmtErrorf", SmithyGoDependency.FMT.func("Errorf")),
                        Map.entry("esConstructor", opEventStreamConstructor),
                        Map.entry("esStruct", opEventStreamSymbol),
                        Map.entry("newMetadata",
                            SmithyGoDependency.SMITHY_MIDDLEWARE.valueSymbol("Metadata")),
                        Map.entry("addToMetadata",
                            SmithyGoDependency.SMITHY_MIDDLEWARE.func("AddEventStreamOutputToMetadata")),
                        Map.entry("writerSetup", inputInfo.isPresent()
                                ? writerSetup(inputInfo.get().getEventStreamTarget().asUnionShape().get())
                                : (Writable) w -> {}),
                        Map.entry("wireWriter", inputInfo.isPresent()
                                ? (Writable) w -> w.write("stream.Writer = eventWriter")
                                : (Writable) w -> {}),
                        Map.entry("readerSetup", outputInfo.isPresent()
                                ? v2ReaderSetup(outputInfo.get().getEventStreamTarget().asUnionShape().get())
                                : (Writable) w -> {}),
                        Map.entry("wireReader", outputInfo.isPresent()
                                ? (Writable) w -> w.write("stream.Reader = eventReader")
                                : (Writable) w -> {}),
                        Map.entry("asyncError", outputInfo.isPresent()
                                ? (Writable) w -> w.write("asyncResult <- deserializeResult{err: err}")
                                : (Writable) w -> {}),
                        Map.entry("asyncPipe", outputInfo.isPresent()
                                ? (Writable) w -> w.write("asyncResult <- deserializeResult{reader: resp.Body}")
                                : (Writable) w -> {})
                ));
    }

    private Writable v2ReaderSetup(UnionShape outputEventStream) {
        var service = ctx.service();
        var schemaName = "schemas." + SchemaGenerator.getSchemaName(outputEventStream, service);
        var adapterConstructor = EventStreamGenerator
                .getEventStreamReaderAdapterConstructor(service, outputEventStream);

        return goTemplate("""
                asyncResult := make(chan deserializeResult, 1)
                asyncReader := newAsyncEventStreamReader(asyncResult)
                eventReader := $newAdapter:L(
                    $newReader:T(m.options.Protocol, $schema:L, TypeRegistry, asyncReader.pipeReader),
                )
                """,
                MapUtils.of(
                        "newAdapter", adapterConstructor,
                        "newReader",
                            SmithyGoDependency.SMITHY_HTTP_TRANSPORT.func("NewEventStreamReader"),
                        "schema", schemaName
                ));
    }
}
