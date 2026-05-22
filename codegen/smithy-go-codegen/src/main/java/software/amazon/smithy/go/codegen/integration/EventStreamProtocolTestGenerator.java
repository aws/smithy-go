package software.amazon.smithy.go.codegen.integration;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoDependency;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.ShapeValueGenerator;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.EventStreamIndex;
import software.amazon.smithy.model.knowledge.EventStreamInfo;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.protocoltests.traits.AppliesTo;
import software.amazon.smithy.protocoltests.traits.eventstream.Event;
import software.amazon.smithy.protocoltests.traits.eventstream.EventHeaderValue;
import software.amazon.smithy.protocoltests.traits.eventstream.EventStreamTestCase;
import software.amazon.smithy.protocoltests.traits.eventstream.EventStreamTestsTrait;
import software.amazon.smithy.protocoltests.traits.eventstream.EventType;

/**
 * Generates protocol unit tests for event stream operations from
 * {@code @eventStreamTests} trait definitions. Uses a mock HTTP client
 * (no real server) to test serialization and deserialization.
 */
public class EventStreamProtocolTestGenerator {
    private static final Logger LOGGER = Logger.getLogger(EventStreamProtocolTestGenerator.class.getName());

    private static final GoDependency AWS_EVENTSTREAM = GoDependency.moduleDependency(
            "github.com/aws/aws-sdk-go-v2/aws/protocol/eventstream",
            "github.com/aws/aws-sdk-go-v2/aws/protocol/eventstream",
            "v0.0.0", "eventstream");

    private static final GoDependency AWS_CREDENTIALS = GoDependency.moduleDependency(
            "github.com/aws/aws-sdk-go-v2/credentials",
            "github.com/aws/aws-sdk-go-v2/credentials",
            "v0.0.0", "credentials");

    private static final Set<String> SKIP_TESTS = Set.of(
            // SDK does not validate required fields on responses client-side.
            "MissingRequiredInitialResponseOutput",
            "DuplexMissingRequiredInitialResponseOutput",

            // SDK does not validate the type of the :event-type header. A blob
            // value is treated as an unknown event (UnknownUnionMember) rather
            // than surfacing an error.
            "MalformedEventTypeOutput",
            "DuplexMalformedEventTypeOutput"
    );

    private final GoSettings settings;
    private final Model model;
    private final ServiceShape service;
    private final SymbolProvider symbolProvider;
    private final GoDelegator delegator;
    private final EventStreamIndex eventStreamIndex;

    public EventStreamProtocolTestGenerator(GoCodegenContext ctx) {
        this.settings = ctx.settings();
        this.model = ctx.model();
        this.service = ctx.settings().getService(ctx.model());
        this.symbolProvider = ctx.symbolProvider();
        this.delegator = (GoDelegator) ctx.writerDelegator();
        this.eventStreamIndex = EventStreamIndex.of(model);
    }

    public void generateProtocolTests() {
        TopDownIndex topDownIndex = model.getKnowledge(TopDownIndex.class);

        boolean hasAnyTests = false;
        for (OperationShape operation : new TreeSet<>(topDownIndex.getContainedOperations(service))) {
            if (operation.hasTrait(EventStreamTestsTrait.class)) {
                var trait = operation.expectTrait(EventStreamTestsTrait.class);
                List<EventStreamTestCase> clientTests = trait.getTestCasesFor(AppliesTo.CLIENT)
                        .stream()
                        .filter(tc -> tc.getProtocol().equals(settings.getProtocol()))
                        .collect(Collectors.toList());
                if (!clientTests.isEmpty()) {
                    hasAnyTests = true;
                    break;
                }
            }
        }

        if (!hasAnyTests) {
            return;
        }

        // Generate the harness file
        delegator.useFileWriter("eventstream_protocol_test.go", settings.getModuleName(), writer -> {
            generateHarnessFile(writer);
        });

        // Generate tests per operation
        for (OperationShape operation : new TreeSet<>(topDownIndex.getContainedOperations(service))) {
            operation.getTrait(EventStreamTestsTrait.class).ifPresent(trait -> {
                List<EventStreamTestCase> clientTests = trait.getTestCasesFor(AppliesTo.CLIENT)
                        .stream()
                        .filter(tc -> tc.getProtocol().equals(settings.getProtocol()))
                        .collect(Collectors.toList());

                if (clientTests.isEmpty()) {
                    return;
                }

                delegator.useShapeTestWriter(operation, writer -> {
                    LOGGER.fine(() -> "Generating event stream protocol tests for " + operation.getId());
                    generateTestFile(writer, operation, clientTests);
                });
            });
        }
    }

    private void generateHarnessFile(GoWriter writer) {
        writer.addUseImports(SmithyGoDependency.BYTES);
        writer.addUseImports(SmithyGoDependency.IO);
        writer.addUseImports(SmithyGoDependency.NET_HTTP);
        writer.addUseImports(SmithyGoDependency.STRINGS);
        writer.addUseImports(SmithyGoDependency.SYNC);
        // FUTURE(serde2): event stream runtime goes over to smithy so this will go away
        writer.addUseImports(AWS_EVENTSTREAM);

        writer.write("""
                // eventstreamMockHTTPClient implements aws.HTTPClient for event stream testing.
                type eventstreamMockHTTPClient struct {
                    StatusCode       int
                    Header           http.Header
                    ResponseEvents   []eventstream.Message
                    ResponseBody     string
                    KeepResponseOpen bool

                    requestBuf   *bytes.Buffer
                    requestDone  chan struct{}
                    responseDone chan struct{}
                    closeOnce    sync.Once
                }

                func newEventstreamMockHTTPClient() *eventstreamMockHTTPClient {
                    return &eventstreamMockHTTPClient{
                        StatusCode:   200,
                        Header:       http.Header{},
                        requestBuf:   &bytes.Buffer{},
                        requestDone:  make(chan struct{}),
                        responseDone: make(chan struct{}),
                    }
                }

                // CloseResponse signals the mock to close the response body pipe.
                func (m *eventstreamMockHTTPClient) CloseResponse() {
                    m.closeOnce.Do(func() { close(m.responseDone) })
                }

                func (m *eventstreamMockHTTPClient) Do(r *http.Request) (*http.Response, error) {
                    go func() {
                        defer close(m.requestDone)
                        if r.Body != nil {
                            io.Copy(m.requestBuf, r.Body)
                        }
                    }()

                    var body io.ReadCloser
                    if m.StatusCode >= 400 {
                        body = io.NopCloser(strings.NewReader(m.ResponseBody))
                    } else {
                        pr, pw := io.Pipe()
                        go func() {
                            encoder := eventstream.NewEncoder()
                            for _, msg := range m.ResponseEvents {
                                if err := encoder.Encode(pw, msg); err != nil {
                                    pw.CloseWithError(err)
                                    return
                                }
                            }
                            if m.KeepResponseOpen {
                                <-m.responseDone
                            }
                            pw.Close()
                        }()
                        body = pr
                    }

                    return &http.Response{
                        StatusCode: m.StatusCode,
                        Header:     m.Header.Clone(),
                        Body:       body,
                        Request:    r,
                        Proto:      "HTTP/2.0",
                        ProtoMajor: 2,
                        ProtoMinor: 0,
                    }, nil
                }

                // readRequestEvents decodes all event stream messages from the captured
                // request body. It unwraps SigV4 signing envelopes (the actual event is
                // in the payload of each signing frame). Must be called after the stream
                // is closed.
                func (m *eventstreamMockHTTPClient) readRequestEvents() ([]eventstream.Message, error) {
                    <-m.requestDone

                    var msgs []eventstream.Message
                    decoder := eventstream.NewDecoder()
                    reader := bytes.NewReader(m.requestBuf.Bytes())
                    for {
                        msg, err := decoder.Decode(reader, make([]byte, 1024))
                        if err == io.EOF {
                            break
                        }
                        if err != nil {
                            return msgs, err
                        }

                        // Skip empty end-of-stream frames
                        if len(msg.Payload) == 0 {
                            continue
                        }

                        // Unwrap: the payload is the actual event message
                        inner, err := decoder.Decode(bytes.NewReader(msg.Payload), make([]byte, 1024))
                        if err != nil {
                            return msgs, err
                        }
                        msgs = append(msgs, inner)
                    }
                    return msgs, nil
                }

                """);
    }

    private void generateTestFile(GoWriter writer, OperationShape operation, List<EventStreamTestCase> testCases) {
        String opName = symbolProvider.toSymbol(operation).getName();

        writer.addUseImports(SmithyGoDependency.TESTING);
        writer.addUseImports(SmithyGoDependency.CONTEXT);
        writer.addUseImports(SmithyGoDependency.SMITHY_MIDDLEWARE);

        boolean hasResponseEvents = testCases.stream()
                .filter(tc -> !SKIP_TESTS.contains(tc.getId()))
                .anyMatch(tc ->
                        tc.getEvents().stream().anyMatch(e -> e.getType() == EventType.RESPONSE));
        boolean hasRequestEvents = testCases.stream()
                .filter(tc -> !SKIP_TESTS.contains(tc.getId()))
                .anyMatch(tc ->
                        tc.getEvents().stream().anyMatch(e -> e.getType() == EventType.REQUEST));
        if (hasResponseEvents || hasRequestEvents) {
            writer.addUseImports(AWS_EVENTSTREAM);
        }

        boolean needsTime = testCases.stream().anyMatch(tc ->
                tc.getEvents().stream()
                        .filter(e -> e.getType() == EventType.RESPONSE)
                        .filter(e -> !e.getBytes().isPresent())
                        .anyMatch(e ->
                                e.getHeaders().values().stream().anyMatch(h ->
                                        h.getType() == EventHeaderValue.Type.TIMESTAMP)));
        if (needsTime) {
            writer.addUseImports(SmithyGoDependency.TIME);
        }

        boolean hasInputStream = eventStreamIndex.getInputInfo(operation).isPresent();
        boolean hasOutputStream = eventStreamIndex.getOutputInfo(operation).isPresent();

        for (EventStreamTestCase testCase : testCases) {
            generateSingleTest(writer, opName, operation, testCase, hasInputStream, hasOutputStream);
        }
    }

    private void generateSingleTest(
            GoWriter writer, String opName, OperationShape operation,
            EventStreamTestCase testCase, boolean hasInputStream, boolean hasOutputStream
    ) {
        String testName = String.format("TestEventStream_%s_%s", opName, testCase.getId());

        List<Event> responseEvents = testCase.getEvents().stream()
                .filter(e -> e.getType() == EventType.RESPONSE)
                .collect(Collectors.toList());
        List<Event> requestEvents = testCase.getEvents().stream()
                .filter(e -> e.getType() == EventType.REQUEST)
                .collect(Collectors.toList());

        boolean expectFailure = testCase.getExpectation().isFailure();
        Optional<Integer> responseCode = testCase.getInitialResponse()
                .flatMap(r -> r.getNumberMember("code"))
                .map(n -> n.getValue().intValue());
        boolean isErrorResponse = responseCode.isPresent() && responseCode.get() >= 400;
        boolean expectsOperationError = expectFailure && responseEvents.isEmpty() && !isErrorResponse;
        boolean needsResp = !expectsOperationError &&
                ((hasOutputStream && !responseEvents.isEmpty())
                        || (hasInputStream && !requestEvents.isEmpty())
                        || isErrorResponse);

        writer.openBlock("func $L(t *testing.T) {", "}", testName, () -> {
            if (SKIP_TESTS.contains(testCase.getId())) {
                writer.write("t.Skip(\"skipped, see SKIP_TESTS in EventStreamProtocolTestGenerator\")");
                return;
            }

            // Build mock HTTP client
            writer.write("mock := newEventstreamMockHTTPClient()");
            writer.write("defer mock.CloseResponse()");

            // Keep response pipe open for tests that read events or send on duplex streams
            if (!responseEvents.isEmpty() || (hasInputStream && hasOutputStream && !requestEvents.isEmpty())) {
                writer.write("mock.KeepResponseOpen = true");
            }

            if (isErrorResponse) {
                writer.write("mock.StatusCode = $L", responseCode.get());
                testCase.getInitialResponse().flatMap(r -> r.getStringMember("body")).ifPresent(body ->
                        writer.write("mock.ResponseBody = $S", body));
            }

            // Set response events
            if (!responseEvents.isEmpty()) {
                writer.openBlock("mock.ResponseEvents = []eventstream.Message{", "}", () -> {
                    for (Event event : responseEvents) {
                        generateMessageLiteral(writer, event);
                    }
                });
            }
            writer.write("");

            // Create client with mock — provide fake creds for signing
            writer.addUseImports(AWS_CREDENTIALS);
            writer.openBlock("client := New(Options{", "})", () -> {
                writer.write("HTTPClient: mock,");
                writer.write("Credentials: credentials.NewStaticCredentialsProvider(\"AKID\", \"SECRET\", \"SESSION\"),");
                writer.write("EndpointResolverV2: &protocolTestEndpointResolver{URL: \"http://localhost\"},");
                writer.openBlock("APIOptions: []func(*middleware.Stack) error{", "},", () -> {
                    writer.openBlock("func(s *middleware.Stack) error {", "},", () -> {
                        writer.write("s.Finalize.Remove(\"Retry\")");
                        writer.write("s.Initialize.Remove(\"OperationInputValidation\")");
                        writer.write("return nil");
                    });
                });
            });
            writer.write("");

            // Invoke operation
            Symbol opSymbol = symbolProvider.toSymbol(operation);
            if (needsResp) {
                writer.write("resp, err := client.$L(context.Background(), &$LInput{})",
                        opSymbol.getName(), opSymbol.getName());
            } else {
                writer.write("_, err := client.$L(context.Background(), &$LInput{})",
                        opSymbol.getName(), opSymbol.getName());
            }

            if (expectsOperationError) {
                writer.openBlock("if err == nil {", "}", () ->
                        writer.write("t.Fatal(\"expected error, got none\")"));
                return;
            }

            writer.openBlock("if err != nil {", "}", () ->
                    writer.write("t.Fatalf(\"expect no error, got %v\", err)"));

            // For error HTTP responses: the SDK surfaces these asynchronously
            // through the stream error, not as an operation-level error.
            if (isErrorResponse) {
                writer.write("<-resp.GetInitialReply()");
                writer.openBlock("if err := resp.GetStream().Err(); err == nil {", "}", () ->
                        writer.write("t.Fatal(\"expected stream error, got none\")"));
                return;
            }

            // For output streams: read and assert events
            if (hasOutputStream && !responseEvents.isEmpty()) {
                writer.write("defer resp.GetStream().Close()");
                writer.write("mock.CloseResponse()");
                writer.write("");

                // Collect events with params for assertion
                List<Event> assertableEvents = responseEvents.stream()
                        .filter(e -> e.getParams().isPresent())
                        .collect(Collectors.toList());

                writer.write("var receivedEvents int");
                if (!expectFailure && !assertableEvents.isEmpty()) {
                    generateEventAssertions(writer, operation, responseEvents);
                } else {
                    writer.openBlock("for event := range resp.GetStream().Events() {", "}", () -> {
                        writer.write("_ = event");
                        writer.write("receivedEvents++");
                    });
                }
                writer.write("");

                if (expectFailure) {
                    writer.openBlock("if err := resp.GetStream().Err(); err == nil {", "}", () ->
                            writer.write("t.Fatal(\"expected stream error, got none\")"));
                } else {
                    writer.openBlock("if err := resp.GetStream().Err(); err != nil {", "}", () ->
                            writer.write("t.Fatalf(\"expect no stream error, got %v\", err)"));
                    writer.openBlock("if receivedEvents != $L {", "}", responseEvents.size(), () ->
                            writer.write("t.Fatalf(\"expected $L events, got %d\", receivedEvents)", responseEvents.size()));
                }
            }

            // For input streams: send events, close, and assert serialized messages.
            if (hasInputStream && !requestEvents.isEmpty()) {
                generateInputEventSendAndAssert(writer, operation, requestEvents);
            }
        });
        writer.write("");
    }

    private void generateEventAssertions(GoWriter writer, OperationShape operation, List<Event> responseEvents) {
        EventStreamInfo outputInfo = eventStreamIndex.getOutputInfo(operation).get();
        UnionShape eventUnion = outputInfo.getEventStreamTarget().asUnionShape().get();
        Symbol unionSymbol = symbolProvider.toSymbol(eventUnion);

        writer.addUseImports(SmithyGoDependency.SMITHY_TESTING);

        writer.openBlock("for event := range resp.GetStream().Events() {", "}", () -> {
            writer.write("receivedEvents++");
            writer.openBlock("switch receivedEvents {", "}", () -> {
                int idx = 0;
                for (Event event : responseEvents) {
                    idx++;
                    if (event.getParams().isEmpty()) {
                        writer.write("case $L:", idx);
                        writer.write("// no params to assert");
                        continue;
                    }

                    ObjectNode params = event.getParams().get();
                    // params is { unionMemberName: { structFields... } }
                    String memberName = params.getMembers().keySet().iterator().next().getValue();
                    ObjectNode memberParams = params.expectObjectMember(memberName).expectObjectNode();

                    MemberShape unionMember = eventUnion.getMember(memberName).get();
                    StructureShape targetShape = model.expectShape(unionMember.getTarget(), StructureShape.class);

                    // Wrapper type is {UnionName}Member{MemberName} in the types package
                    String wrapperName = unionSymbol.getName() + "Member"
                            + memberName.substring(0, 1).toUpperCase() + memberName.substring(1);
                    Symbol wrapperSymbol = unionSymbol.toBuilder()
                            .name(wrapperName)
                            .build();

                    writer.openBlock("case $L:", "", idx, () -> {
                        writer.writeInline("expect := &$T{Value: ", wrapperSymbol);
                        new ShapeValueGenerator(settings, model, symbolProvider,
                                ShapeValueGenerator.Config.builder().build())
                                .writeStructureShapeValueInline(writer, targetShape, memberParams);
                        writer.write("}");
                        writer.openBlock("if err := smithytesting.CompareValues(expect, event); err != nil {", "}", () ->
                                writer.write("t.Errorf(\"event %d mismatch: %v\", receivedEvents, err)"));
                    });
                }
            });
        });
    }

    private void generateInputEventSendAndAssert(
            GoWriter writer, OperationShape operation, List<Event> requestEvents
    ) {
        EventStreamInfo inputInfo = eventStreamIndex.getInputInfo(operation).get();
        UnionShape eventUnion = inputInfo.getEventStreamTarget().asUnionShape().get();
        Symbol unionSymbol = symbolProvider.toSymbol(eventUnion);

        writer.addImport("bytes", "bytes");
        writer.addImport("encoding/base64", "base64");

        // Send each event (skip error events — they aren't sendable union members)
        for (Event event : requestEvents) {
            if (event.getParams().isEmpty()) {
                continue;
            }

            ObjectNode params = event.getParams().get();
            String memberName = params.getMembers().keySet().iterator().next().getValue();

            MemberShape unionMember = eventUnion.getMember(memberName).get();
            StructureShape targetShape = model.expectShape(unionMember.getTarget(), StructureShape.class);
            if (targetShape.hasTrait(software.amazon.smithy.model.traits.ErrorTrait.class)) {
                continue;
            }

            ObjectNode memberParams = params.expectObjectMember(memberName).expectObjectNode();

            String wrapperName = unionSymbol.getName() + "Member"
                    + memberName.substring(0, 1).toUpperCase() + memberName.substring(1);
            Symbol wrapperSymbol = unionSymbol.toBuilder()
                    .name(wrapperName)
                    .build();

            writer.writeInline("sendEvent := &$T{Value: ", wrapperSymbol);
            new ShapeValueGenerator(settings, model, symbolProvider,
                    ShapeValueGenerator.Config.builder().build())
                    .writeStructureShapeValueInline(writer, targetShape, memberParams);
            writer.write("}");
            writer.openBlock("if err := resp.GetStream().Send(context.Background(), sendEvent); err != nil {", "}", () ->
                    writer.write("t.Fatalf(\"failed to send event: %v\", err)"));
        }

        // Close the stream to flush
        writer.write("mock.CloseResponse()");
        writer.write("resp.GetStream().Close()");
        writer.write("");

        // Filter to only sendable (non-error) events for assertion
        List<Event> sendableEvents = requestEvents.stream()
                .filter(e -> e.getParams().isPresent())
                .filter(e -> {
                    String mn = e.getParams().get().getMembers().keySet().iterator().next().getValue();
                    MemberShape um = eventUnion.getMember(mn).get();
                    StructureShape ts = model.expectShape(um.getTarget(), StructureShape.class);
                    return !ts.hasTrait(software.amazon.smithy.model.traits.ErrorTrait.class);
                })
                .collect(Collectors.toList());

        if (sendableEvents.isEmpty()) {
            return;
        }

        // Read captured messages and assert
        writer.write("requestMsgs, err := mock.readRequestEvents()");
        writer.openBlock("if err != nil {", "}", () ->
                writer.write("t.Fatalf(\"failed to read request events: %v\", err)"));
        writer.openBlock("if len(requestMsgs) != $L {", "}", sendableEvents.size(), () ->
                writer.write("t.Fatalf(\"expected $L request events, got %d\", len(requestMsgs))",
                        sendableEvents.size()));
        writer.write("");

        // Assert each message
        int idx = 0;
        for (Event event : sendableEvents) {
            final int eventIdx = idx;
            if (event.getBytes().isPresent()) {
                // Compare full message bytes
                byte[] raw = event.getBytes().get();
                writer.openBlock("{", "}", () -> {
                    writer.write("expectRaw, _ := base64.StdEncoding.DecodeString($S)",
                            Base64.getEncoder().encodeToString(raw));
                    writer.write("expectMsg, _ := eventstream.NewDecoder().Decode(bytes.NewReader(expectRaw), make([]byte, 1024))");
                    writer.write("actualMsg := requestMsgs[$L]", eventIdx);
                    // Compare headers
                    writer.openBlock("for _, eh := range expectMsg.Headers {", "}", () -> {
                        writer.write("ah := actualMsg.Headers.Get(eh.Name)");
                        writer.openBlock("if ah == nil {", "}", () ->
                                writer.write("t.Errorf(\"request event %d: missing header %q\", $L, eh.Name)", eventIdx));
                        writer.openBlock("if ah != nil && ah.String() != eh.Value.String() {", "}", () ->
                                writer.write("t.Errorf(\"request event %d: header %q = %v, want %v\", $L, eh.Name, ah, eh.Value)",
                                        eventIdx));
                    });
                    // Compare payload
                    writer.openBlock("if !bytes.Equal(actualMsg.Payload, expectMsg.Payload) {", "}", () ->
                            writer.write("t.Errorf(\"request event %d: payload mismatch, got %q want %q\", $L, actualMsg.Payload, expectMsg.Payload)",
                                    eventIdx));
                });
            } else {
                // Compare individual headers and body from the event definition
                Map<String, EventHeaderValue<?>> headers = event.getHeaders();
                if (!headers.isEmpty()) {
                    writer.openBlock("{", "}", () -> {
                        writer.write("actualMsg := requestMsgs[$L]", eventIdx);
                        for (Map.Entry<String, EventHeaderValue<?>> entry : headers.entrySet()) {
                            writer.write("if h := actualMsg.Headers.Get($S); h == nil {", entry.getKey());
                            writer.write("    t.Errorf(\"request event %d: missing header %q\", $L, $S)", eventIdx, entry.getKey());
                            writer.write("}");
                        }
                    });
                }
                event.getBody().ifPresent(body -> {
                    writer.openBlock("if !bytes.Equal(requestMsgs[$L].Payload, []byte($S)) {", "}", eventIdx, body, () ->
                            writer.write("t.Errorf(\"request event %d: payload mismatch\", $L)", eventIdx));
                });
            }
            idx++;
        }
    }

    private void generateMessageLiteral(GoWriter writer, Event event) {
        // If bytes are provided, decode and use directly
        if (event.getBytes().isPresent()) {
            byte[] raw = event.getBytes().get();
            writer.addImport("bytes", "bytes");
            writer.addImport("encoding/base64", "base64");
            writer.openBlock("func() eventstream.Message {", "}(),", () -> {
                writer.write("raw, _ := base64.StdEncoding.DecodeString($S)", Base64.getEncoder().encodeToString(raw));
                writer.write("msg, err := eventstream.NewDecoder().Decode(bytes.NewReader(raw), make([]byte, 1024))");
                writer.openBlock("if err != nil {", "}", () -> writer.write("panic(err)"));
                writer.write("return msg");
            });
            return;
        }

        // Build from headers + body
        writer.openBlock("{", "},", () -> {
            Map<String, EventHeaderValue<?>> headers = event.getHeaders();
            if (!headers.isEmpty()) {
                writer.openBlock("Headers: eventstream.Headers{", "},", () -> {
                    for (Map.Entry<String, EventHeaderValue<?>> entry : headers.entrySet()) {
                        generateHeaderLiteral(writer, entry.getKey(), entry.getValue());
                    }
                });
            }

            event.getBody().ifPresent(body ->
                    writer.write("Payload: []byte($S),", body));
        });
    }

    private void generateHeaderLiteral(GoWriter writer, String name, EventHeaderValue<?> value) {
        String valueExpr;
        switch (value.getType()) {
            case BOOLEAN:
                valueExpr = String.format("eventstream.BoolValue(%s)", value.asBoolean());
                break;
            case BYTE:
                valueExpr = String.format("eventstream.Int8Value(%d)", value.asByte());
                break;
            case SHORT:
                valueExpr = String.format("eventstream.Int16Value(%d)", value.asShort());
                break;
            case INTEGER:
                valueExpr = String.format("eventstream.Int32Value(%d)", value.asInteger());
                break;
            case LONG:
                valueExpr = String.format("eventstream.Int64Value(%d)", value.asLong());
                break;
            case BLOB:
                writer.addImport("encoding/base64", "base64");
                valueExpr = String.format("eventstream.BytesValue(func() []byte { b, _ := base64.StdEncoding.DecodeString(%s); return b }())",
                        "\"" + Base64.getEncoder().encodeToString(value.asBlob()) + "\"");
                break;
            case STRING:
                valueExpr = String.format("eventstream.StringValue(\"%s\")", value.asString());
                break;
            case TIMESTAMP:
                valueExpr = String.format(
                        "eventstream.TimestampValue(func() time.Time { t, _ := time.Parse(time.RFC3339, \"%s\"); return t }())",
                        value.asString());
                break;
            default:
                throw new IllegalArgumentException("unexpected header type: " + value.getType());
        }
        writer.write("{Name: $S, Value: $L},", name, valueExpr);
    }
}
