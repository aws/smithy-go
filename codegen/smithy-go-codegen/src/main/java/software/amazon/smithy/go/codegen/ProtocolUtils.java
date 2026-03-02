package software.amazon.smithy.go.codegen;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.integration.HttpProtocolTestGenerator;
import software.amazon.smithy.go.codegen.integration.HttpProtocolUnitTestGenerator;
import software.amazon.smithy.go.codegen.integration.HttpProtocolUnitTestGenerator.ConfigValue;
import software.amazon.smithy.go.codegen.integration.HttpProtocolUnitTestRequestGenerator;
import software.amazon.smithy.go.codegen.integration.HttpProtocolUnitTestResponseErrorGenerator;
import software.amazon.smithy.go.codegen.integration.HttpProtocolUnitTestResponseGenerator;
import software.amazon.smithy.go.codegen.integration.IdempotencyTokenMiddlewareGenerator;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.protocoltests.traits.HttpRequestTestsTrait;
import software.amazon.smithy.protocoltests.traits.HttpResponseTestsTrait;
import software.amazon.smithy.utils.SetUtils;

public final class ProtocolUtils {
    private ProtocolUtils() {
    }

    /**
     * Generates HTTP protocol tests.
     */
    public static void generateHttpProtocolTests(GoCodegenContext ctx) {
        var operations = TopDownIndex.of(ctx.model()).getContainedOperations(ctx.settings().getService());
        var hasRequestTests = operations.stream()
                .anyMatch(it -> it.hasTrait(HttpRequestTestsTrait.class));
        var hasResponseTests = operations.stream()
                .anyMatch(it -> it.hasTrait(HttpResponseTestsTrait.class));
        if (hasRequestTests || hasResponseTests) {
            ctx.writerDelegator().useFileWriter("protocol_test.go", ctx.settings().getModuleName(), writer -> {
                writer.addUseImports(SmithyGoDependency.CONTEXT);
                writer.addUseImports(SmithyGoDependency.FMT);
                writer.addUseImports(SmithyGoDependency.IO);
                writer.addUseImports(SmithyGoDependency.NET_HTTP);
                writer.addUseImports(SmithyGoDependency.NET_URL);
                writer.addUseImports(SmithyGoDependency.SMITHY_ENDPOINTS);
                writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_TRANSPORT);
                writer.addUseImports(SmithyGoDependency.SMITHY_MIDDLEWARE);
                writer.addUseImports(SmithyGoDependency.STRCONV);
                writer.addUseImports(SmithyGoDependency.STRINGS);
                writer.write("""
                        type protocolTestHTTPClient struct {}

                        func (*protocolTestHTTPClient) Do(r *http.Request) (*http.Response, error) {
                            return &http.Response{
                                StatusCode: 200,
                                Header:     r.Header,
                                Body:       io.NopCloser(strings.NewReader("")),
                                Request:    r,
                            }, nil
                        }

                        func (*protocolTestEndpointResolver) Do(r *http.Request) (*http.Response, error) {
                            return &http.Response{
                                StatusCode: 200,
                                Header:     r.Header,
                                Body:       io.NopCloser(strings.NewReader("")),
                                Request:    r,
                            }, nil
                        }

                        type protocolTestEndpointResolver struct {
                            URL string
                        }

                        func (r *protocolTestEndpointResolver) ResolveEndpoint(ctx context.Context, params EndpointParameters) (
                            smithyendpoints.Endpoint, error,
                        ) {
                            u, err := url.Parse(r.URL)
                            if err != nil {
                                return smithyendpoints.Endpoint{}, err
                            }

                            return smithyendpoints.Endpoint{URI: *u}, nil
                        }

                        type captureRequestMiddleware struct {
                            req *http.Request
                        }

                        func (*captureRequestMiddleware) ID() string {
                            return "captureRequest"
                        }

                        func (m *captureRequestMiddleware) HandleFinalize(
                            ctx context.Context, input middleware.FinalizeInput, next middleware.FinalizeHandler,
                        ) (
                            out middleware.FinalizeOutput, md middleware.Metadata, err error,
                        ) {
                            req, ok := input.Request.(*smithyhttp.Request)
                            if !ok {
                                return out, md, fmt.Errorf("unexpected transport type %T", req)
                            }

                            *m.req = *req.Build(ctx)

                            if len(m.req.URL.RawPath) == 0 || m.req.URL.RawPath == "/" {
                                m.req.URL.RawPath = m.req.URL.Path
                            }
                            if v := m.req.ContentLength; v != 0 {
                                m.req.Header.Set("Content-Length", strconv.FormatInt(v, 10))
                            }

                            return next.HandleFinalize(ctx, input)
                        }
                        """);
            });
        }

        Set<HttpProtocolUnitTestGenerator.ConfigValue> configValues = new TreeSet<>(SetUtils.of(
                HttpProtocolUnitTestGenerator.ConfigValue.builder()
                        .name("EndpointResolverV2")
                        .value(writer -> writer.write("&protocolTestEndpointResolver{serverURL},"))
                        .build(),
                HttpProtocolUnitTestGenerator.ConfigValue.builder()
                        .name("APIOptions")
                        .value(writer -> {
                            Symbol stackSymbol = SymbolUtils.createPointableSymbolBuilder("Stack",
                                    SmithyGoDependency.SMITHY_MIDDLEWARE).build();
                            writer.openBlock("[]func($P) error{", "},", stackSymbol, () -> {
                                writer.openBlock("func(s $P) error {", "},", stackSymbol, () -> {
                                    writer.write("s.Finalize.Clear()");
                                    writer.write("s.Initialize.Remove(`OperationInputValidation`)");
                                    writer.write("return nil");
                                });
                            });
                        })
                        .build()
        ));

        var model = ctx.model();

        if (IdempotencyTokenMiddlewareGenerator.hasOperationsWithIdempotencyToken(model, ctx.settings().getService(model))) {
            configValues.add(
                    HttpProtocolUnitTestGenerator.ConfigValue.builder()
                            .name(IdempotencyTokenMiddlewareGenerator.IDEMPOTENCY_CONFIG_NAME)
                            .value(writer -> {
                                writer.addUseImports(SmithyGoDependency.SMITHY_RAND);
                                writer.addUseImports(SmithyGoDependency.SMITHY_TESTING);
                                writer.write("smithyrand.NewUUIDIdempotencyToken(&smithytesting.ByteLoop{}),");
                            })
                            .build()
            );
        }

        Set<ConfigValue> inputConfigValues = new TreeSet<>(configValues);
        inputConfigValues.add(HttpProtocolUnitTestGenerator.ConfigValue.builder()
                .name("HTTPClient")
                .value(writer -> writer.write("&protocolTestHTTPClient{},"))
                .build());

        // skip request compression tests, not yet implemented in the SDK
        Set<HttpProtocolUnitTestGenerator.SkipTest> inputSkipTests = new TreeSet<>(SetUtils.of(
                // CBOR default value serialization (SHOULD)
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("smithy.protocoltests.rpcv2Cbor#RpcV2Protocol"))
                        .operation(ShapeId.from("smithy.protocoltests.rpcv2Cbor#OperationWithDefaults"))
                        .addTestName("RpcV2CborClientPopulatesDefaultValuesInInput")
                        .addTestName("RpcV2CborClientSkipsTopLevelDefaultValuesInInput")
                        .addTestName("RpcV2CborClientUsesExplicitlyProvidedMemberValuesOverDefaults")
                        .addTestName("RpcV2CborClientUsesExplicitlyProvidedValuesInTopLevel")
                        .addTestName("RpcV2CborClientIgnoresNonTopLevelDefaultsOnMembersWithClientOptional")
                        .build(),

                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.restxml#RestXml"))
                        .operation(ShapeId.from("aws.protocoltests.restxml#HttpPayloadWithUnion"))
                        .addTestName("RestXmlHttpPayloadWithUnion")
                        .addTestName("RestXmlHttpPayloadWithUnsetUnion")
                        .build(),

                // REST-JSON default value serialization
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.restjson#RestJson"))
                        .operation(ShapeId.from("aws.protocoltests.restjson#OperationWithDefaults"))
                        .addTestName("RestJsonClientPopulatesDefaultValuesInInput")
                        .addTestName("RestJsonClientUsesExplicitlyProvidedValuesInTopLevel")
                        .build(),
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.restjson#RestJson"))
                        .operation(ShapeId.from("aws.protocoltests.restjson#OperationWithNestedStructure"))
                        .addTestName("RestJsonClientPopulatesNestedDefaultValuesWhenMissing")
                        .build(),

                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.json10#JsonRpc10"))
                        .operation(ShapeId.from("aws.protocoltests.json10#OperationWithDefaults"))
                        .addTestName("AwsJson10ClientPopulatesDefaultValuesInInput")
                        .addTestName("AwsJson10ClientSkipsTopLevelDefaultValuesInInput")
                        .addTestName("AwsJson10ClientUsesExplicitlyProvidedMemberValuesOverDefaults")
                        .addTestName("AwsJson10ClientUsesExplicitlyProvidedValuesInTopLevel")
                        .build(),

                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.json10#JsonRpc10"))
                        .operation(ShapeId.from("aws.protocoltests.json10#OperationWithNestedStructure"))
                        .addTestName("AwsJson10ClientPopulatesNestedDefaultValuesWhenMissing")
                        .build(),

                // since the introduction of endpoints 2.0 we have not been preserving the trailing / if a custom
                // endpoint has a path prefix, so rather than re-breaking that behavior we skip these tests for now
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.json10#JsonRpc10"))
                        .operation(ShapeId.from("aws.protocoltests.json10#HostWithPathOperation"))
                        .addTestName("AwsJson10HostWithPath")
                        .build(),
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.json#JsonProtocol"))
                        .operation(ShapeId.from("aws.protocoltests.json#HostWithPathOperation"))
                        .addTestName("AwsJson11HostWithPath")
                        .build(),
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.query#AwsQuery"))
                        .operation(ShapeId.from("aws.protocoltests.query#HostWithPathOperation"))
                        .addTestName("QueryHostWithPath")
                        .build(),
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.ec2#AwsEc2"))
                        .operation(ShapeId.from("aws.protocoltests.ec2#HostWithPathOperation"))
                        .addTestName("Ec2QueryHostWithPath")
                        .build()
        ));

        Set<HttpProtocolUnitTestGenerator.SkipTest> outputSkipTests = new TreeSet<>(SetUtils.of(
                // CBOR default value deserialization (SHOULD)
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("smithy.protocoltests.rpcv2Cbor#RpcV2Protocol"))
                        .operation(ShapeId.from("smithy.protocoltests.rpcv2Cbor#OperationWithDefaults"))
                        .addTestName("RpcV2CborClientPopulatesDefaultsValuesWhenMissingInResponse")
                        .addTestName("RpcV2CborClientIgnoresDefaultValuesIfMemberValuesArePresentInResponse")
                        .build(),
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("smithy.protocoltests.rpcv2Cbor#RpcV2Protocol"))
                        .operation(ShapeId.from("smithy.protocoltests.rpcv2Cbor#RpcV2CborDenseMaps"))
                        .addTestName("RpcV2CborDeserializesDenseSetMapAndSkipsNull")
                        .build(),

                // REST-JSON optional (SHOULD) test cases
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.restjson#RestJson"))
                        .operation(ShapeId.from("aws.protocoltests.restjson#JsonMaps"))
                        .addTestName("RestJsonDeserializesDenseSetMapAndSkipsNull")
                        .build(),

                // REST-JSON default value deserialization
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.restjson#RestJson"))
                        .operation(ShapeId.from("aws.protocoltests.restjson#OperationWithDefaults"))
                        .addTestName("RestJsonClientPopulatesDefaultsValuesWhenMissingInResponse")
                        .build(),
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.restjson#RestJson"))
                        .operation(ShapeId.from("aws.protocoltests.restjson#OperationWithNestedStructure"))
                        .addTestName("RestJsonClientPopulatesNestedDefaultsWhenMissingInResponseBody")
                        .build(),

                // REST-XML opinionated test - prefix headers as empty vs nil map
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.restxml#RestXml"))
                        .operation(ShapeId.from("aws.protocoltests.restxml#HttpPrefixHeaders"))
                        .addTestName("HttpPrefixHeadersAreNotPresent")
                        .build(),

                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.restjson#RestJson"))
                        .operation(ShapeId.from("aws.protocoltests.restjson#JsonUnions"))
                        .addTestName("RestJsonDeserializeIgnoreType")
                        .build(),
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.json10#JsonRpc10"))
                        .operation(ShapeId.from("aws.protocoltests.json10#JsonUnions"))
                        .addTestName("AwsJson10DeserializeIgnoreType")
                        .build(),
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.json#JsonProtocol"))
                        .operation(ShapeId.from("aws.protocoltests.json#JsonUnions"))
                        .addTestName("AwsJson11DeserializeIgnoreType")
                        .build(),

                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.json10#JsonRpc10"))
                        .operation(ShapeId.from("aws.protocoltests.json10#OperationWithDefaults"))
                        .addTestName("AwsJson10ClientPopulatesDefaultsValuesWhenMissingInResponse")
                        .addTestName("AwsJson10ClientIgnoresDefaultValuesIfMemberValuesArePresentInResponse")
                        .build(),
                // We don't populate default values if none are sent by the server
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.json10#JsonRpc10"))
                        .operation(ShapeId.from("aws.protocoltests.json10#OperationWithNestedStructure"))
                        .addTestName("AwsJson10ClientPopulatesNestedDefaultsWhenMissingInResponseBody")
                        .build(),
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.json10#JsonRpc10"))
                        .operation(ShapeId.from("aws.protocoltests.json10#OperationWithRequiredMembers"))
                        .addTestName("AwsJson10ClientErrorCorrectsWhenServerFailsToSerializeRequiredValues")
                        .build(),
                HttpProtocolUnitTestGenerator.SkipTest.builder()
                        .service(ShapeId.from("aws.protocoltests.json10#JsonRpc10"))
                        .operation(ShapeId.from("aws.protocoltests.json10#OperationWithRequiredMembersWithDefaults"))
                        .addTestName("AwsJson10ClientErrorCorrectsWithDefaultValuesWhenServerFailsToSerializeRequiredValues")
                        .build()
        ));

        new HttpProtocolTestGenerator(ctx,
                (HttpProtocolUnitTestRequestGenerator.Builder) new HttpProtocolUnitTestRequestGenerator
                        .Builder()
                        .settings(ctx.settings())
                        .addSkipTests(inputSkipTests)
                        .addClientConfigValues(inputConfigValues),
                (HttpProtocolUnitTestResponseGenerator.Builder) new HttpProtocolUnitTestResponseGenerator
                        .Builder()
                        .settings(ctx.settings())
                        .addSkipTests(outputSkipTests)
                        .addClientConfigValues(configValues),
                (HttpProtocolUnitTestResponseErrorGenerator.Builder) new HttpProtocolUnitTestResponseErrorGenerator
                        .Builder()
                        .settings(ctx.settings())
                        .addClientConfigValues(configValues)
        ).generateProtocolTests();
    }
}
