package software.amazon.smithy.go.codegen.integration;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.ArrayList;
import java.util.Set;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.EventStreamIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.protocol.traits.Rpcv2CborTrait;
import software.amazon.smithy.utils.MapUtils;

public class SerdeSnapshotTests implements GoIntegration {
    private static final Set<String> SKIP_OPERATIONS = Set.of(
            // predict has a customization where an input member goes into the url, which complicates things, just skip it
            "com.amazonaws.machinelearning#Predict"
    );

    @Override
    public void writeAdditionalFiles(
            GoSettings settings, Model model, SymbolProvider symbolProvider, GoDelegator goDelegator
    ) {
        goDelegator.useFileWriter("serde_snapshot_test.go", settings.getModuleName(), writer -> {
            writer.addBuildTag("serde_snapshot");
            writer.write(commonSource());
            writer.write(bodyEqual(isCbor(settings)));

            var service = settings.getService(model);
            var generator = new SnapshotInputGenerator(model, symbolProvider);
            writer.write(snapshotTests(model, service, symbolProvider, generator));
            writer.write(snapshotUpdaters(model, service, symbolProvider, generator));
        });
    }

    private static boolean isCbor(GoSettings settings) {
        return Rpcv2CborTrait.ID.equals(settings.getProtocol());
    }

    // Emits serdeBodyEqual, the request-body comparator. Most protocols serialize
    // deterministically, so a raw byte compare is correct. rpcv2Cbor encodes struct
    // fields as a CBOR map and the encoder emits map entries in Go map iteration
    // order, so the same input produces different byte orderings across runs. For
    // that protocol we compare decoded CBOR values, which is order-independent.
    private Writable bodyEqual(boolean isCbor) {
        if (!isCbor) {
            return goTemplate("""
                    func serdeBodyEqual(got, expected []byte) bool {
                        return bytes.Equal(got, expected)
                    }
                    """);
        }
        return writer -> {
            writer.addUseImports(SmithyGoDependency.REFLECT);
            writer.addUseImports(SmithyGoDependency.SMITHY_CBOR);
            writer.write("""
                    func serdeBodyEqual(got, expected []byte) bool {
                        if len(got) == 0 || len(expected) == 0 {
                            return bytes.Equal(got, expected)
                        }
                        gv, gerr := smithycbor.Decode(got)
                        ev, eerr := smithycbor.Decode(expected)
                        if gerr != nil || eerr != nil {
                            return bytes.Equal(got, expected)
                        }
                        return reflect.DeepEqual(gv, ev)
                    }
                    """);
        };
    }

    private Writable commonSource() {
        return writer -> {
            writer.addUseImports(SmithyGoDependency.OS);
            writer.addUseImports(SmithyGoDependency.FS);
            writer.addUseImports(SmithyGoDependency.IO);
            writer.addUseImports(SmithyGoDependency.ERRORS);
            writer.addUseImports(SmithyGoDependency.FMT);
            writer.addUseImports(SmithyGoDependency.BYTES);
            writer.addUseImports(SmithyGoDependency.CONTEXT);
            writer.addUseImports(SmithyGoDependency.SMITHY_MIDDLEWARE);
            writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_TRANSPORT);
            writer.addUseImports(SmithyGoDependency.STRINGS);
            writer.addUseImports(SmithyGoDependency.SLICES);
            writer.write("""
                    const serdeSSPrefix = "serde_snapshot"

                    // errSerdeSnapshotOK is returned by the capture middleware to abort the request
                    // pipeline once the serialized request has been captured, so no network call is
                    // made. It is a control signal, not a failure.
                    var errSerdeSnapshotOK = errors.New("serde snapshot: request captured")

                    func serdeCreatePath(path string) (*os.File, error) {
                        if err := os.MkdirAll(serdeSSPrefix, 0700); err != nil && !errors.Is(err, fs.ErrExist) {
                            return nil, err
                        }
                        return os.Create(path)
                    }

                    func serdeSSPath(op string) string {
                        return fmt.Sprintf("%s/%s.request.snap", serdeSSPrefix, op)
                    }

                    type captureSerdeRequestMiddleware struct {
                        body     *bytes.Buffer
                        method   *string
                        rawPath  *string
                        rawQuery *string
                        header   *map[string][]string
                    }

                    func (*captureSerdeRequestMiddleware) ID() string { return "captureSerdeRequest" }

                    func (m *captureSerdeRequestMiddleware) HandleFinalize(
                        ctx context.Context, input middleware.FinalizeInput, next middleware.FinalizeHandler,
                    ) (
                        middleware.FinalizeOutput, middleware.Metadata, error,
                    ) {
                        req, ok := input.Request.(*smithyhttp.Request)
                        if !ok {
                            return middleware.FinalizeOutput{}, middleware.Metadata{}, fmt.Errorf("unexpected transport type %T", input.Request)
                        }

                        built := req.Build(ctx)
                        *m.method = built.Method
                        *m.rawPath = built.URL.RawPath
                        if *m.rawPath == "" {
                            *m.rawPath = built.URL.Path
                        }
                        *m.rawQuery = built.URL.RawQuery
                        *m.header = built.Header

                        if built.Body != nil {
                            if _, err := io.Copy(m.body, built.Body); err != nil {
                                return middleware.FinalizeOutput{}, middleware.Metadata{}, err
                            }
                        }

                        return middleware.FinalizeOutput{}, middleware.Metadata{}, errSerdeSnapshotOK
                    }

                    func serdeFormatRequest(method, rawPath, rawQuery string, header map[string][]string, body []byte) string {
                        skipHeaders := map[string]bool{
                            "Authorization": true,
                            "User-Agent": true,
                            "X-Amz-Date": true,
                            "X-Amz-Security-Token": true,
                            "Amz-Sdk-Invocation-Id": true,
                            "Amz-Sdk-Request": true,
                        }

                        var sb strings.Builder
                        sb.WriteString(method)
                        sb.WriteString(" ")
                        sb.WriteString(rawPath)
                        if rawQuery != "" {
                            sb.WriteString("?")
                            sb.WriteString(rawQuery)
                        }
                        sb.WriteString("\\n\\n")

                        keys := make([]string, 0, len(header))
                        for k := range header {
                            if skipHeaders[k] {
                                continue
                            }
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
                        if len(body) > 0 {
                            sb.Write(body)
                        }
                        return sb.String()
                    }

                    func serdeUpdateSnapshot(method, rawPath, rawQuery string, header map[string][]string, body []byte, operation string) error {
                        content := serdeFormatRequest(method, rawPath, rawQuery, header, body)
                        // Leave the snapshot untouched if it's semantically equal to the new one.
                        // Some protocols (rpcv2Cbor) serialize map/struct fields in a nondeterministic
                        // byte order, so a blind rewrite would churn the file on every run.
                        if existing, err := os.ReadFile(serdeSSPath(operation)); err == nil {
                            prefix := serdeFormatRequest(method, rawPath, rawQuery, header, nil)
                            if strings.HasPrefix(string(existing), prefix) &&
                                serdeBodyEqual(body, []byte(string(existing)[len(prefix):])) {
                                return nil
                            }
                        }
                        f, err := serdeCreatePath(serdeSSPath(operation))
                        if err != nil {
                            return err
                        }
                        defer f.Close()
                        if _, err := f.Write([]byte(content)); err != nil {
                            return err
                        }
                        return nil
                    }

                    func serdeTestSnapshot(method, rawPath, rawQuery string, header map[string][]string, body []byte, operation string) error {
                        f, err := os.Open(serdeSSPath(operation))
                        if errors.Is(err, fs.ErrNotExist) {
                            return nil
                        }
                        if err != nil {
                            return err
                        }
                        defer f.Close()
                        expected, err := io.ReadAll(f)
                        if err != nil {
                            return err
                        }
                        prefix := serdeFormatRequest(method, rawPath, rawQuery, header, nil)
                        if !strings.HasPrefix(string(expected), prefix) ||
                            !serdeBodyEqual(body, []byte(string(expected)[len(prefix):])) {
                            content := serdeFormatRequest(method, rawPath, rawQuery, header, body)
                            return fmt.Errorf("serde snapshot mismatch for %s:\\nGOT:\\n%s:\\nEXPECTED:\\n%s", operation, content, string(expected))
                        }
                        return nil
                    }

                    type serdeEndpointResolver struct{}

                    func (*serdeEndpointResolver) ResolveEndpoint(ctx context.Context, params EndpointParameters) (smithyendpoints.Endpoint, error) {
                        return smithyendpoints.Endpoint{URI: url.URL{Scheme: "https", Host: "test.example.com"}}, nil
                    }

                    func serdeNewClient() *Client {
                        return New(Options{
                            Region: "us-east-1",
                            EndpointResolverV2: &serdeEndpointResolver{},
                        })
                    }
                    """);
            writer.addUseImports(SmithyGoDependency.NET_URL);
            writer.addUseImports(SmithyGoDependency.SMITHY_ENDPOINTS);
        };
    }

    private Writable snapshotTests(
            Model model, ServiceShape service, SymbolProvider symbolProvider, SnapshotInputGenerator generator
    ) {
        var eventStreamIndex = EventStreamIndex.of(model);
        var writables = new ArrayList<Writable>();
        for (var operation : TopDownIndex.of(model).getContainedOperations(service)) {
            if (SKIP_OPERATIONS.contains(operation.getId().toString())
                    || eventStreamIndex.getInputInfo(operation).isPresent()
                    || eventStreamIndex.getOutputInfo(operation).isPresent()) {
                continue;
            }
            for (var testCase : generator.generateCases(operation)) {
                writables.add(writeTestCheck(operation, testCase, symbolProvider));
            }
        }
        return ChainWritable.of(writables).compose();
    }

    private Writable snapshotUpdaters(
            Model model, ServiceShape service, SymbolProvider symbolProvider, SnapshotInputGenerator generator
    ) {
        var eventStreamIndex = EventStreamIndex.of(model);
        var writables = new ArrayList<Writable>();
        for (var operation : TopDownIndex.of(model).getContainedOperations(service)) {
            if (SKIP_OPERATIONS.contains(operation.getId().toString())
                    || eventStreamIndex.getInputInfo(operation).isPresent()
                    || eventStreamIndex.getOutputInfo(operation).isPresent()) {
                continue;
            }
            for (var testCase : generator.generateCases(operation)) {
                writables.add(writeTestUpdate(operation, testCase, symbolProvider));
            }
        }
        return ChainWritable.of(writables).compose();
    }

    private String testName(OperationShape operation, SnapshotInputGenerator.TestCase testCase,
                            SymbolProvider symbolProvider) {
        var opName = symbolProvider.toSymbol(operation).getName();
        if (testCase.suffix().isEmpty()) {
            return opName;
        }
        return opName + "_" + testCase.suffix();
    }

    private Writable writeTestCheck(
            OperationShape operation, SnapshotInputGenerator.TestCase testCase, SymbolProvider symbolProvider
    ) {
        var name = testName(operation, testCase, symbolProvider);
        var opName = symbolProvider.toSymbol(operation).getName();
        return goTemplate("""
                func TestSerdeCheckSnapshot_$name:L(t $testingT:P) {
                    input := $input:W
                    body := &bytes.Buffer{}
                    method := ""
                    rawPath := ""
                    rawQuery := ""
                    header := map[string][]string{}
                    svc := serdeNewClient()
                    _, err := svc.$op:L($contextBackground:T(), input, func(o *Options) {
                        o.APIOptions = append(o.APIOptions, func(stack $middlewareStack:P) error {
                            stack.Initialize.Remove("OperationInputValidation")
                            stack.Serialize.Remove("RequestCompression")
                            return stack.Finalize.Add(&captureSerdeRequestMiddleware{
                                body: body, method: &method, rawPath: &rawPath, rawQuery: &rawQuery, header: &header,
                            }, $middlewareBefore:T)
                        })
                    })
                    if err != nil && !errors.Is(err, errSerdeSnapshotOK) {
                        t.Fatal(err)
                    }
                    if err := serdeTestSnapshot(method, rawPath, rawQuery, header, body.Bytes(), $name:S); err != nil {
                        t.Fatal(err)
                    }
                }
                """,
                MapUtils.of(
                        "name", name,
                        "op", opName,
                        "input", testCase.input(),
                        "testingT", GoStdlibTypes.Testing.T,
                        "contextBackground", GoStdlibTypes.Context.Background,
                        "middlewareStack", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("Stack"),
                        "middlewareBefore", SmithyGoDependency.SMITHY_MIDDLEWARE.valueSymbol("Before")
                ));
    }

    private Writable writeTestUpdate(
            OperationShape operation, SnapshotInputGenerator.TestCase testCase, SymbolProvider symbolProvider
    ) {
        var name = testName(operation, testCase, symbolProvider);
        var opName = symbolProvider.toSymbol(operation).getName();
        return goTemplate("""
                func TestSerdeUpdateSnapshot_$name:L(t $testingT:P) {
                    input := $input:W
                    body := &bytes.Buffer{}
                    method := ""
                    rawPath := ""
                    rawQuery := ""
                    header := map[string][]string{}
                    svc := serdeNewClient()
                    _, err := svc.$op:L($contextBackground:T(), input, func(o *Options) {
                        o.APIOptions = append(o.APIOptions, func(stack $middlewareStack:P) error {
                            stack.Initialize.Remove("OperationInputValidation")
                            stack.Serialize.Remove("RequestCompression")
                            return stack.Finalize.Add(&captureSerdeRequestMiddleware{
                                body: body, method: &method, rawPath: &rawPath, rawQuery: &rawQuery, header: &header,
                            }, $middlewareBefore:T)
                        })
                    })
                    if err != nil && !errors.Is(err, errSerdeSnapshotOK) {
                        t.Fatal(err)
                    }
                    if err := serdeUpdateSnapshot(method, rawPath, rawQuery, header, body.Bytes(), $name:S); err != nil {
                        t.Fatal(err)
                    }
                }
                """,
                MapUtils.of(
                        "name", name,
                        "op", opName,
                        "input", testCase.input(),
                        "testingT", GoStdlibTypes.Testing.T,
                        "contextBackground", GoStdlibTypes.Context.Background,
                        "middlewareStack", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("Stack"),
                        "middlewareBefore", SmithyGoDependency.SMITHY_MIDDLEWARE.valueSymbol("Before")
                ));
    }
}
