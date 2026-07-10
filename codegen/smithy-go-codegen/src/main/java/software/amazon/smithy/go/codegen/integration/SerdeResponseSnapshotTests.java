/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.go.codegen.integration;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import software.amazon.smithy.aws.traits.protocols.AwsJson1_0Trait;
import software.amazon.smithy.aws.traits.protocols.AwsJson1_1Trait;
import software.amazon.smithy.aws.traits.protocols.AwsQueryTrait;
import software.amazon.smithy.aws.traits.protocols.Ec2QueryTrait;
import software.amazon.smithy.aws.traits.protocols.RestJson1Trait;
import software.amazon.smithy.aws.traits.protocols.RestXmlTrait;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.SchemaGenerator;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.EventStreamIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.traits.HttpErrorTrait;
import software.amazon.smithy.protocol.traits.Rpcv2CborTrait;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.StringUtils;

public class SerdeResponseSnapshotTests implements GoIntegration {
    private static final Set<String> SKIP_OPERATIONS = Set.of(
            // Predict has a customization where an input member goes into the URL. The check path still builds a
            // request to drive the deserialize, so that customization complicates things here too -- just skip it.
            "com.amazonaws.machinelearning#Predict"
    );

    @Override
    public void writeAdditionalFiles(
            GoSettings settings, Model model, SymbolProvider symbolProvider, GoDelegator goDelegator
    ) {
        var service = settings.getService(model);
        var protoNew = resolveProtocolCtor(settings.getProtocol());
        if (protoNew == null) {
            return;
        }

        if (sortedOperations(model, service, EventStreamIndex.of(model)).isEmpty()) {
            return;
        }

        var serviceSchemaRef = "schemas." + StringUtils.capitalize(service.getId().getName(service));
        var generator = new SnapshotOutputGenerator(model, symbolProvider);

        // The Check tests are ALWAYS generated: they only read a fixture, inject it, run the real deserialize path,
        // and compare the output. They have no dependency on output Serialize/schemas, so they compile and run whether
        // the service is on legacy serde or schema-serde, and skip cleanly when no fixture is committed yet (services
        // are migrated to schema-serde in waves).
        goDelegator.useFileWriter("response_snapshot_test.go", settings.getModuleName(), writer -> {
            writer.addBuildTag("response_snapshot");
            writer.write(checkCommonSource());
            writer.write(checks(model, service, symbolProvider, generator));
        });

        // The Update tests (fixture generation) serialize the output value via a throwaway protocol, which requires
        // the generated output Serialize method + schemas package. Those only exist under schema-serde, so this file
        // is generated only when schema-serde is enabled -- otherwise the service would not compile.
        if (!settings.useLegacySerde()) {
            goDelegator.useFileWriter("response_snapshot_update_test.go", settings.getModuleName(), writer -> {
                writer.addBuildTag("response_snapshot");
                writer.addImport(settings.getModuleName() + "/schemas", "schemas");
                writer.write(updateCommonSource());
                writer.write(updaters(model, service, symbolProvider, generator, serviceSchemaRef, protoNew,
                        errorFraming(settings.getProtocol())));
            });
        }
    }

    // Resolves the throwaway protocol constructor used to serialize output values into wire responses. Returns null
    // only for protocols we don't recognize (the file is then not emitted). Every schema-serde protocol is wired up;
    // the response-snapshot files are still only emitted for a service once it migrates to schema-serde (the Update
    // half is gated on !useLegacySerde), so this generalizes without any manual per-protocol flip.
    private Symbol resolveProtocolCtor(ShapeId protocol) {
        if (Rpcv2CborTrait.ID.equals(protocol)) {
            return SmithyGoDependency.SMITHY_PROTOCOL_RPCV2.func("NewCBOR");
        } else if (AwsJson1_0Trait.ID.equals(protocol)) {
            return SmithyGoDependency.SMITHY_PROTOCOL_AWSJSON.func("New10");
        } else if (AwsJson1_1Trait.ID.equals(protocol)) {
            return SmithyGoDependency.SMITHY_PROTOCOL_AWSJSON.func("New11");
        } else if (RestJson1Trait.ID.equals(protocol)) {
            return SmithyGoDependency.SMITHY_PROTOCOL_RESTJSON1.func("New");
        } else if (RestXmlTrait.ID.equals(protocol)) {
            return SmithyGoDependency.SMITHY_PROTOCOL_RESTXML.func("New");
        } else if (AwsQueryTrait.ID.equals(protocol)) {
            return SmithyGoDependency.SMITHY_PROTOCOL_AWSQUERY.func("New");
        } else if (Ec2QueryTrait.ID.equals(protocol)) {
            return SmithyGoDependency.SMITHY_PROTOCOL_EC2QUERY.func("New");
        }
        return null;
    }

    // How the modeled-error discriminator + status are framed onto the captured wire response, per protocol. The
    // success path is protocol-agnostic (serialize output, capture headers+body, status 200); only errors need
    // protocol-specific framing because the throwaway SerializeRequest emits just the error members, not the
    // protocol's error envelope/discriminator that the deserializer keys on.
    private enum ErrorFraming {
        // rpcv2Cbor: discriminator is "__type" inside the CBOR body map (no header form).
        CBOR_BODY,
        // awsJson1_0/1_1 + restJson1: deserializers resolve the code from the X-Amzn-ErrorType header first, so we
        // set that and leave the serialized JSON members as the body (which the error deserializer then reads).
        JSON_HEADER,
        // restXml/awsQuery/ec2Query: deserializers parse an <ErrorResponse><Error><Code>..</Code>..</Error>..
        // envelope. awsQuery/ec2Query serialize requests as form-urlencoded rather than XML, so for those two the
        // captured body is not XML members.
        XML_ENVELOPE
    }

    private ErrorFraming errorFraming(ShapeId protocol) {
        if (Rpcv2CborTrait.ID.equals(protocol)) {
            return ErrorFraming.CBOR_BODY;
        } else if (RestXmlTrait.ID.equals(protocol) || AwsQueryTrait.ID.equals(protocol)
                || Ec2QueryTrait.ID.equals(protocol)) {
            return ErrorFraming.XML_ENVELOPE;
        }
        return ErrorFraming.JSON_HEADER;
    }

    // HTTP status for an error fixture: explicit @httpError code, else Smithy's default (400 client / 500 server).
    private int errorStatus(StructureShape error) {
        if (error.hasTrait(HttpErrorTrait.class)) {
            return error.expectTrait(HttpErrorTrait.class).getCode();
        }
        var errorTrait = error.getTrait(ErrorTrait.class).orElse(null);
        if (errorTrait != null && "server".equals(errorTrait.getValue())) {
            return 500;
        }
        return 400;
    }

    // checkCommonSource emits helpers used by the (always-generated) Check tests: fixture path + reader, the injecting
    // client, and the endpoint resolver. No dependency on schema-serde-only symbols.
    private Writable checkCommonSource() {
        return writer -> {
            writer.addUseImports(SmithyGoDependency.OS);
            writer.addUseImports(SmithyGoDependency.FS);
            writer.addUseImports(SmithyGoDependency.IO);
            writer.addUseImports(SmithyGoDependency.BUFIO);
            writer.addUseImports(SmithyGoDependency.ERRORS);
            writer.addUseImports(SmithyGoDependency.FMT);
            writer.addUseImports(SmithyGoDependency.BYTES);
            writer.addUseImports(SmithyGoDependency.STRCONV);
            writer.addUseImports(SmithyGoDependency.STRINGS);
            writer.addUseImports(SmithyGoDependency.CONTEXT);
            writer.addUseImports(SmithyGoDependency.NET_HTTP);
            writer.addUseImports(SmithyGoDependency.NET_URL);
            writer.addUseImports(SmithyGoDependency.SMITHY_MIDDLEWARE);
            writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_TRANSPORT);
            writer.addUseImports(SmithyGoDependency.SMITHY_ENDPOINTS);
            writer.write("""
                    const serdeRespSSPrefix = "response_snapshot"

                    func serdeRespSSPath(op string) string {
                        return fmt.Sprintf("%s/%s.snap", serdeRespSSPrefix, op)
                    }

                    // serdeRespReadSnapshot parses a frozen wire response fixture: a status-code
                    // line, HTTP headers, a blank line, then the raw body.
                    func serdeRespReadSnapshot(op string) (int, http.Header, []byte, error) {
                        raw, err := os.ReadFile(serdeRespSSPath(op))
                        if err != nil {
                            return 0, nil, nil, err
                        }

                        r := bufio.NewReader(bytes.NewReader(raw))
                        statusLine, err := r.ReadString('\\n')
                        if err != nil {
                            return 0, nil, nil, fmt.Errorf("read status line: %w", err)
                        }
                        status, err := strconv.Atoi(strings.TrimSpace(statusLine))
                        if err != nil {
                            return 0, nil, nil, fmt.Errorf("parse status: %w", err)
                        }

                        header := http.Header{}
                        for {
                            line, err := r.ReadString('\\n')
                            if err != nil && err != io.EOF {
                                return 0, nil, nil, err
                            }
                            trimmed := strings.TrimRight(line, "\\r\\n")
                            if trimmed == "" {
                                break
                            }
                            k, v, ok := strings.Cut(trimmed, ": ")
                            if !ok {
                                return 0, nil, nil, fmt.Errorf("malformed header %q", trimmed)
                            }
                            header.Add(k, v)
                            if err == io.EOF {
                                break
                            }
                        }

                        body, err := io.ReadAll(r)
                        if err != nil {
                            return 0, nil, nil, err
                        }
                        return status, header, body, nil
                    }

                    type serdeRespEndpointResolver struct{}

                    func (*serdeRespEndpointResolver) ResolveEndpoint(ctx context.Context, params EndpointParameters) (smithyendpoints.Endpoint, error) {
                        return smithyendpoints.Endpoint{URI: url.URL{Scheme: "https", Host: "test.example.com"}}, nil
                    }

                    func serdeRespClient(status int, header http.Header, body []byte) *Client {
                        return New(Options{
                            Region: "us-east-1",
                            HTTPClient: smithyhttp.ClientDoFunc(func(req *http.Request) (*http.Response, error) {
                                resp := &http.Response{StatusCode: status, Header: header, Request: req}
                                if len(body) > 0 {
                                    resp.ContentLength = int64(len(body))
                                    resp.Body = io.NopCloser(bytes.NewReader(body))
                                } else {
                                    resp.Body = http.NoBody
                                }
                                return resp, nil
                            }),
                            EndpointResolverV2: &serdeRespEndpointResolver{},
                            APIOptions: []func(*middleware.Stack) error{
                                func(s *middleware.Stack) error {
                                    s.Finalize.Clear()
                                    s.Initialize.Remove("OperationInputValidation")
                                    return nil
                                },
                            },
                        })
                    }
                    """);
        };
    }

    // updateCommonSource emits helpers used only by the (schema-serde-gated) Update tests: fixture directory creation
    // and the fixture writer. serdeRespSSPath / serdeRespSSPrefix live in the always-generated Check file.
    private Writable updateCommonSource() {
        return writer -> {
            writer.addUseImports(SmithyGoDependency.OS);
            writer.addUseImports(SmithyGoDependency.FS);
            writer.addUseImports(SmithyGoDependency.IO);
            writer.addUseImports(SmithyGoDependency.ERRORS);
            writer.addUseImports(SmithyGoDependency.STRCONV);
            writer.addUseImports(SmithyGoDependency.STRINGS);
            writer.addUseImports(SmithyGoDependency.SLICES);
            writer.addUseImports(SmithyGoDependency.NET_HTTP);
            writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_TRANSPORT);
            writer.write("""
                    func serdeRespCreatePath(path string) (*os.File, error) {
                        if err := os.MkdirAll(serdeRespSSPrefix, 0700); err != nil && !errors.Is(err, fs.ErrExist) {
                            return nil, err
                        }
                        return os.Create(path)
                    }

                    func serdeRespWriteSnapshot(op string, status int, header http.Header, body []byte) error {
                        f, err := serdeRespCreatePath(serdeRespSSPath(op))
                        if err != nil {
                            return err
                        }
                        defer f.Close()

                        var sb strings.Builder
                        sb.WriteString(strconv.Itoa(status))
                        sb.WriteString("\\n")
                        keys := make([]string, 0, len(header))
                        for k := range header {
                            keys = append(keys, k)
                        }
                        slices.Sort(keys)
                        for _, k := range keys {
                            for _, v := range header[k] {
                                sb.WriteString(k)
                                sb.WriteString(": ")
                                sb.WriteString(v)
                                sb.WriteString("\\n")
                            }
                        }
                        sb.WriteString("\\n")
                        sb.Write(body)
                        _, err = f.WriteString(sb.String())
                        return err
                    }

                    // serdeRespXMLErrorEnvelope wraps serialized error members in the XML
                    // error envelope the restXml/query deserializers parse:
                    // <ErrorResponse><Error><Code>CODE</Code>MEMBERS</Error></ErrorResponse>.
                    // It strips the serialized body's outer root element and re-parents the
                    // members under <Error>.
                    func serdeRespXMLErrorEnvelope(body []byte, code string) []byte {
                        inner := ""
                        s := strings.TrimSpace(string(body))
                        if strings.HasPrefix(s, "<?xml") {
                            if i := strings.Index(s, "?>"); i >= 0 {
                                s = strings.TrimSpace(s[i+2:])
                            }
                        }
                        if start := strings.Index(s, ">"); start >= 0 {
                            if end := strings.LastIndex(s, "<"); end > start {
                                inner = s[start+1 : end]
                            }
                        }
                        return []byte("<ErrorResponse><Error><Code>" + code + "</Code>" + inner + "</Error></ErrorResponse>")
                    }
                    """);
        };
    }

    private Writable checks(
            Model model, ServiceShape service, SymbolProvider symbolProvider, SnapshotOutputGenerator generator
    ) {
        var eventStreamIndex = EventStreamIndex.of(model);
        var writables = new ArrayList<Writable>();
        var operations = sortedOperations(model, service, eventStreamIndex);
        for (var operation : operations) {
            var inputSymbol = symbolProvider.toSymbol(model.expectShape(operation.getInputShape()));
            for (var testCase : generator.generateCases(operation)) {
                writables.add(writeCheck(operation, testCase, symbolProvider, inputSymbol));
            }
        }
        // Modeled errors run the same ReadStruct/ReadUnion deserialize machinery as success outputs (the
        // smithy-go#671 defect class). Under schema-serde an error is resolved against the whole service type
        // registry, so its deser behavior is a property of the error SHAPE, not of (operation, error). Snapshot
        // each unique error shape ONCE, via a deterministic representative operation that models it -- so the
        // legacy before/after check (legacy only recognizes errors modeled on the op) stays valid.
        for (var entry : errorRepOps(model, operations).entrySet()) {
            var errorShape = entry.getKey();
            var repOp = entry.getValue();
            var inputSymbol = symbolProvider.toSymbol(model.expectShape(repOp.getInputShape()));
            var errorSymbol = symbolProvider.toSymbol(errorShape);
            for (var errorCase : generator.generateCasesForError(errorShape)) {
                writables.add(writeErrorCheck(repOp, errorCase, symbolProvider, inputSymbol, errorSymbol));
            }
        }
        return ChainWritable.of(writables).compose();
    }

    // Returns the service's non-skipped operations sorted by shape id, so the representative-operation choice for
    // each error (and thus fixture/test names) is stable across regenerations.
    private List<OperationShape> sortedOperations(Model model, ServiceShape service, EventStreamIndex esi) {
        var operations = new ArrayList<OperationShape>();
        for (var operation : TopDownIndex.of(model).getContainedOperations(service)) {
            if (!skip(operation, esi)) {
                operations.add(operation);
            }
        }
        operations.sort(Comparator.comparing(o -> o.getId().toString()));
        return operations;
    }

    // Maps each modeled error shape to a single, stable representative operation: the first operation (in sorted
    // order) that models it. Deterministic regardless of how many operations return the error, so re-running codegen
    // never reshuffles which op an error's fixture is generated/checked through.
    private LinkedHashMap<StructureShape, OperationShape> errorRepOps(
            Model model, List<OperationShape> sortedOps
    ) {
        var repByErrorId = new LinkedHashMap<ShapeId, OperationShape>();
        for (var operation : sortedOps) {
            var errorIds = new ArrayList<>(operation.getErrors());
            errorIds.sort(Comparator.comparing(ShapeId::toString));
            for (var errorId : errorIds) {
                repByErrorId.putIfAbsent(errorId, operation);
            }
        }
        var orderedErrorIds = new ArrayList<>(repByErrorId.keySet());
        orderedErrorIds.sort(Comparator.comparing(ShapeId::toString));
        var result = new LinkedHashMap<StructureShape, OperationShape>();
        for (var errorId : orderedErrorIds) {
            result.put(model.expectShape(errorId, StructureShape.class), repByErrorId.get(errorId));
        }
        return result;
    }

    private Writable updaters(
            Model model, ServiceShape service, SymbolProvider symbolProvider, SnapshotOutputGenerator generator,
            String serviceSchemaRef, Symbol protoNew, ErrorFraming framing
    ) {
        var eventStreamIndex = EventStreamIndex.of(model);
        var writables = new ArrayList<Writable>();
        var operations = sortedOperations(model, service, eventStreamIndex);
        for (var operation : operations) {
            var outputShape = model.expectShape(operation.getOutputShape(), StructureShape.class);
            var opSchemaRef = SchemaGenerator.getSchemaRef(operation, service);
            var outSchemaRef = SchemaGenerator.getSchemaRef(outputShape, service);
            for (var testCase : generator.generateCases(operation)) {
                writables.add(writeUpdate(
                        operation, testCase, symbolProvider, serviceSchemaRef, protoNew, opSchemaRef, outSchemaRef));
            }
        }
        for (var entry : errorRepOps(model, operations).entrySet()) {
            var errorShape = entry.getKey();
            var repOp = entry.getValue();
            var opSchemaRef = SchemaGenerator.getSchemaRef(repOp, service);
            var errSchemaRef = SchemaGenerator.getSchemaRef(errorShape, service);
            var errorSymbol = symbolProvider.toSymbol(errorShape);
            for (var errorCase : generator.generateCasesForError(errorShape)) {
                writables.add(writeErrorUpdate(
                        repOp, errorCase, symbolProvider, serviceSchemaRef, protoNew, opSchemaRef,
                        errSchemaRef, errorSymbol, framing, errorStatus(errorShape)));
            }
        }
        return ChainWritable.of(writables).compose();
    }

    private boolean skip(OperationShape operation, EventStreamIndex eventStreamIndex) {
        return SKIP_OPERATIONS.contains(operation.getId().toString())
                || eventStreamIndex.getInputInfo(operation).isPresent()
                || eventStreamIndex.getOutputInfo(operation).isPresent();
    }

    private Writable writeCheck(
            OperationShape operation, SnapshotInputGenerator.TestCase testCase, SymbolProvider symbolProvider,
            Symbol inputSymbol
    ) {
        var opName = symbolProvider.toSymbol(operation).getName();
        return goTemplate("""
                func TestCheckResponseSnapshot_$name:L(t $testingT:P) {
                    want := $output:W
                    status, header, body, err := serdeRespReadSnapshot($fixture:S)
                    if errors.Is(err, fs.ErrNotExist) {
                        t.Skip("no response snapshot fixture")
                    }
                    if err != nil {
                        t.Fatal(err)
                    }
                    svc := serdeRespClient(status, header, body)
                    got, err := svc.$op:L($ctx:T(), &$input:T{})
                    if err != nil {
                        t.Fatal(err)
                    }
                    if err := $compare:T(want, got); err != nil {
                        t.Errorf("response snapshot mismatch for %s: %v", $fixture:S, err)
                    }
                }
                """,
                MapUtils.of(
                        "name", opName,
                        "fixture", opName + ".response",
                        "op", opName,
                        "output", testCase.input(),
                        "input", inputSymbol,
                        "testingT", GoStdlibTypes.Testing.T,
                        "ctx", GoStdlibTypes.Context.Background,
                        "compare", SmithyGoDependency.SMITHY_TESTING.func("CompareValues")
                ));
    }

    private Writable writeUpdate(
            OperationShape operation, SnapshotInputGenerator.TestCase testCase, SymbolProvider symbolProvider,
            String serviceSchemaRef, Symbol protoNew, String opSchemaRef, String outSchemaRef
    ) {
        var opName = symbolProvider.toSymbol(operation).getName();
        return goTemplate("""
                func TestUpdateResponseSnapshot_$name:L(t $testingT:P) {
                    want := $output:W
                    proto := $protoNew:T($service:L)
                    opSchema := $newOpSchema:T($op:L, $out:L, $out:L)
                    req := smithyhttp.NewStackRequest().(*smithyhttp.Request)
                    if err := proto.SerializeRequest($ctx:T(), opSchema, want, req); err != nil {
                        t.Fatal(err)
                    }
                    built := req.Build($ctx:T())
                    var body []byte
                    if built.Body != nil {
                        b, err := io.ReadAll(built.Body)
                        if err != nil {
                            t.Fatal(err)
                        }
                        body = b
                    }
                    if err := serdeRespWriteSnapshot($fixture:S, 200, built.Header, body); err != nil {
                        t.Fatal(err)
                    }
                }
                """,
                MapUtils.of(
                        "name", opName,
                        "fixture", opName + ".response",
                        "output", testCase.input(),
                        "protoNew", protoNew,
                        "service", serviceSchemaRef,
                        "op", opSchemaRef,
                        "out", outSchemaRef,
                        "newOpSchema", SmithyGoDependency.SMITHY.func("NewOperationSchema"),
                        "testingT", GoStdlibTypes.Testing.T,
                        "ctx", GoStdlibTypes.Context.Background
                ));
    }

    // Emits a Check test for a modeled error: injects the frozen error fixture, calls the op EXPECTING failure,
    // errors.As into the modeled error type, and compares against the deterministic expected value.
    private Writable writeErrorCheck(
            OperationShape operation, SnapshotInputGenerator.TestCase testCase, SymbolProvider symbolProvider,
            Symbol inputSymbol, Symbol errorSymbol
    ) {
        var opName = symbolProvider.toSymbol(operation).getName();
        var errName = errorSymbol.getName();
        var testName = "Error_" + errName;
        var fixtureName = errName + ".error";
        return goTemplate("""
                func TestCheckResponseSnapshot_$name:L(t $testingT:P) {
                    want := $want:W
                    status, header, body, err := serdeRespReadSnapshot($fixture:S)
                    if errors.Is(err, fs.ErrNotExist) {
                        t.Skip("no response snapshot fixture")
                    }
                    if err != nil {
                        t.Fatal(err)
                    }
                    svc := serdeRespClient(status, header, body)
                    _, opErr := svc.$op:L($ctx:T(), &$input:T{})
                    if opErr == nil {
                        t.Fatal("expected error, got nil")
                    }
                    var got $err:P
                    if !errors.As(opErr, &got) {
                        t.Fatalf("expected $err:T, got %v", opErr)
                    }
                    if err := $compare:T(want, got); err != nil {
                        t.Errorf("error response snapshot mismatch for %s: %v", $fixture:S, err)
                    }
                }
                """,
                MapUtils.of(
                        "name", testName,
                        "op", opName,
                        "fixture", fixtureName,
                        "want", testCase.input(),
                        "input", inputSymbol,
                        "err", errorSymbol,
                        "testingT", GoStdlibTypes.Testing.T,
                        "ctx", GoStdlibTypes.Context.Background,
                        "compare", SmithyGoDependency.SMITHY_TESTING.func("CompareValues")
                ));
    }

    // Emits an Update test for a modeled error: serializes the deterministic error value through the throwaway
    // protocol, applies protocol-specific error framing (discriminator + status) so the client routes to the modeled
    // error, and writes a non-2xx fixture.
    private Writable writeErrorUpdate(
            OperationShape operation, SnapshotInputGenerator.TestCase testCase, SymbolProvider symbolProvider,
            String serviceSchemaRef, Symbol protoNew, String opSchemaRef, String errSchemaRef, Symbol errorSymbol,
            ErrorFraming framing, int status
    ) {
        var errName = errorSymbol.getName();
        var testName = "Error_" + errName;
        var fixtureName = errName + ".error";
        return goTemplate("""
                func TestUpdateResponseSnapshot_$name:L(t $testingT:P) {
                    want := $want:W
                    proto := $protoNew:T($service:L)
                    opSchema := $newOpSchema:T($op:L, $err:L, $err:L)
                    req := smithyhttp.NewStackRequest().(*smithyhttp.Request)
                    if err := proto.SerializeRequest($ctx:T(), opSchema, want, req); err != nil {
                        t.Fatal(err)
                    }
                    built := req.Build($ctx:T())
                    var body []byte
                    if built.Body != nil {
                        b, err := io.ReadAll(built.Body)
                        if err != nil {
                            t.Fatal(err)
                        }
                        body = b
                    }
                    $frame:W
                    if err := serdeRespWriteSnapshot($fixture:S, $status:L, built.Header, body); err != nil {
                        t.Fatal(err)
                    }
                }
                """,
                Map.ofEntries(
                        Map.entry("name", testName),
                        Map.entry("fixture", fixtureName),
                        Map.entry("want", testCase.input()),
                        Map.entry("protoNew", protoNew),
                        Map.entry("service", serviceSchemaRef),
                        Map.entry("op", opSchemaRef),
                        Map.entry("err", errSchemaRef),
                        Map.entry("status", status),
                        Map.entry("frame", errorFrameWritable(framing)),
                        Map.entry("newOpSchema", SmithyGoDependency.SMITHY.func("NewOperationSchema")),
                        Map.entry("testingT", GoStdlibTypes.Testing.T),
                        Map.entry("ctx", GoStdlibTypes.Context.Background)
                ));
    }

    // Protocol-specific fragment that stamps the error discriminator onto the captured wire response. Runs with
    // `built` (the built request), `body` (its captured body), and `want` (the modeled error value) in scope;
    // mutates `body` and/or `built.Header`. `want.ErrorCode()` is exactly the key the client's type registry uses.
    private Writable errorFrameWritable(ErrorFraming framing) {
        switch (framing) {
            case CBOR_BODY:
                return goTemplate("""
                        // Inject the CBOR error discriminator into the body map so the deserializer routes to the
                        // modeled error type.
                        var m $cborMap:T
                        if len(body) > 0 {
                            v, err := $cborDecode:T(body)
                            if err != nil {
                                t.Fatal(err)
                            }
                            mm, ok := v.($cborMap:T)
                            if !ok {
                                t.Fatalf("expected cbor map body, got %T", v)
                            }
                            m = mm
                        } else {
                            m = $cborMap:T{}
                        }
                        m["__type"] = $cborString:T(want.ErrorCode())
                        body = $cborEncode:T(m)""",
                        Map.of(
                                "cborMap", SmithyGoDependency.SMITHY_CBOR.valueSymbol("Map"),
                                "cborString", SmithyGoDependency.SMITHY_CBOR.valueSymbol("String"),
                                "cborDecode", SmithyGoDependency.SMITHY_CBOR.func("Decode"),
                                "cborEncode", SmithyGoDependency.SMITHY_CBOR.func("Encode")
                        ));
            case XML_ENVELOPE:
                return goTemplate("""
                        // Wrap the serialized members in the XML error envelope the deserializer parses.
                        body = serdeRespXMLErrorEnvelope(body, want.ErrorCode())""");
            case JSON_HEADER:
            default:
                return goTemplate("""
                        // Route to the modeled error via the standard error-type header; the serialized JSON members
                        // remain the body for the error deserializer.
                        built.Header.Set("X-Amzn-ErrorType", want.ErrorCode())""");
        }
    }
}
