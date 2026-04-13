package software.amazon.smithy.go.codegen.integration;

import static java.lang.String.format;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.ShapeValueGenerator;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.protocoltests.traits.HttpMessageTestCase;
import software.amazon.smithy.protocoltests.traits.HttpRequestTestCase;
import software.amazon.smithy.protocoltests.traits.HttpRequestTestsTrait;
import software.amazon.smithy.protocoltests.traits.HttpResponseTestCase;
import software.amazon.smithy.protocoltests.traits.HttpResponseTestsTrait;

/**
 * Generates protocol unit tests for the HTTP protocol from smithy models.
 */
public class HttpProtocolTestGenerator {
    private static final Logger LOGGER = Logger.getLogger(HttpProtocolTestGenerator.class.getName());

    private final SymbolProvider symbolProvider;
    private final GoSettings settings;
    private final GoDelegator delegator;

    private final Model model;
    private final ServiceShape service;

    private final HttpProtocolUnitTestRequestGenerator.Builder requestTestBuilder;
    private final HttpProtocolUnitTestResponseGenerator.Builder responseTestBuilder;
    private final HttpProtocolUnitTestResponseErrorGenerator.Builder responseErrorTestBuilder;
    private final String SERDE_BENCHMARK_TAG = "serde-benchmark";

    /**
     * Initializes the protocol generator.
     *
     * @param ctx                      codegen context.
     * @param requestTestBuilder       builder that will create a request test generator.
     * @param responseTestBuilder      build that will create a response test generator.
     * @param responseErrorTestBuilder builder that will create a response API error test generator.
     */
    public HttpProtocolTestGenerator(
            GoCodegenContext ctx,
            HttpProtocolUnitTestRequestGenerator.Builder requestTestBuilder,
            HttpProtocolUnitTestResponseGenerator.Builder responseTestBuilder,
            HttpProtocolUnitTestResponseErrorGenerator.Builder responseErrorTestBuilder
    ) {
        this.settings = ctx.settings();
        this.model = ctx.model();
        this.service = ctx.settings().getService(ctx.model());
        this.symbolProvider = ctx.symbolProvider();
        this.delegator = (GoDelegator) ctx.writerDelegator();

        this.requestTestBuilder = requestTestBuilder;
        this.responseTestBuilder = responseTestBuilder;
        this.responseErrorTestBuilder = responseErrorTestBuilder;
    }

    /**
     * Generates the API HTTP protocol tests defined in the smithy model.
     */
    public void generateProtocolTests() {
        OperationIndex operationIndex = model.getKnowledge(OperationIndex.class);
        TopDownIndex topDownIndex = model.getKnowledge(TopDownIndex.class);

        boolean[] hasSerdBenchmarks = {false};

        for (OperationShape operation : new TreeSet<>(topDownIndex.getContainedOperations(service))) {
            if (operation.hasTag("server-only")) {
                continue;
            }

            // 1. Generate test cases for each request.
            operation.getTrait(HttpRequestTestsTrait.class).ifPresent(trait -> {
                final List<HttpRequestTestCase> testCases = filterProtocolTestCases(trait.getTestCases());
                if (testCases.isEmpty()) {
                    return;
                }
                List<HttpRequestTestCase> serdBenchmarkCases = filterSerdBenchmarkTaggedTestCases(testCases);
                if (serdBenchmarkCases.isEmpty()) {
                    delegator.useShapeTestWriter(operation, (writer) -> {
                        LOGGER.fine(() -> format("Generating request protocol test case for %s", operation.getId()));
                        requestTestBuilder.model(model)
                                .symbolProvider(symbolProvider)
                                .service(service)
                                .operation(operation)
                                .testCases(trait.getTestCases())
                                .build()
                                .generateTestFunction(writer);
                    });

                    delegator.useShapeTestWriter(operation, (writer) -> {
                        LOGGER.fine(() -> format("Generating request protocol benchmark for %s", operation.getId()));
                        requestTestBuilder.model(model)
                                .symbolProvider(symbolProvider)
                                .service(service)
                                .operation(operation)
                                .testCases(trait.getTestCases())
                                .build()
                                .generateBenchmarkFunction(writer);
                    });
                } else {
                    hasSerdBenchmarks[0] = true;
                    delegator.useShapeTestWriter(operation, (writer) -> {
                        LOGGER.fine(() -> format("Generating request protocol serialization benchmark for %s", operation.getId()));
                        requestTestBuilder.model(model)
                                .symbolProvider(symbolProvider)
                                .service(service)
                                .operation(operation)
                                .testCases(serdBenchmarkCases)
                                .build()
                                .generateSerdBenchmarkFunction(writer);
                    });
                }
            });

            // 2. Generate test cases for each response.
            operation.getTrait(HttpResponseTestsTrait.class).ifPresent(trait -> {
                final List<HttpResponseTestCase> testCases = filterProtocolTestCases(trait.getTestCases());
                if (testCases.isEmpty()) {
                    return;
                }
                List<HttpResponseTestCase> serdBenchmarkCases = filterSerdBenchmarkTaggedTestCases(testCases);
                if (serdBenchmarkCases.isEmpty()) {
                    delegator.useShapeTestWriter(operation, (writer) -> {
                        LOGGER.fine(() -> format("Generating response protocol test case for %s", operation.getId()));
                        responseTestBuilder.model(model)
                                .symbolProvider(symbolProvider)
                                .service(service)
                                .operation(operation)
                                .testCases(trait.getTestCases())
                                .shapeValueGeneratorConfig(ShapeValueGenerator.Config.builder()
                                        .normalizeHttpPrefixHeaderKeys(true).build())
                                .build()
                                .generateTestFunction(writer);
                    });

                    delegator.useShapeTestWriter(operation, (writer) -> {
                        LOGGER.fine(() -> format("Generating response protocol benchmark for %s", operation.getId()));
                        responseTestBuilder.model(model)
                                .symbolProvider(symbolProvider)
                                .service(service)
                                .operation(operation)
                                .testCases(trait.getTestCases())
                                .shapeValueGeneratorConfig(ShapeValueGenerator.Config.builder()
                                        .normalizeHttpPrefixHeaderKeys(true).build())
                                .build()
                                .generateBenchmarkFunction(writer);
                    });
                } else {
                    hasSerdBenchmarks[0] = true;
                    delegator.useShapeTestWriter(operation, (writer) -> {
                        LOGGER.fine(() -> format("Generating response protocol deserialization benchmark for %s", operation.getId()));
                        responseTestBuilder.model(model)
                                .symbolProvider(symbolProvider)
                                .service(service)
                                .operation(operation)
                                .testCases(serdBenchmarkCases)
                                .shapeValueGeneratorConfig(ShapeValueGenerator.Config.builder()
                                        .normalizeHttpPrefixHeaderKeys(true).build())
                                .build()
                                .generateSerdBenchmarkFunction(writer);
                    });
                }
            });

            // 3. Generate test cases for each error on each operation.
            for (StructureShape error : operationIndex.getErrors(operation)) {
                if (error.hasTag("server-only")) {
                    continue;
                }

                error.getTrait(HttpResponseTestsTrait.class).ifPresent(trait -> {
                    final List<HttpResponseTestCase> testCases = filterProtocolTestCases(trait.getTestCases());
                    if (testCases.isEmpty()) {
                        return;
                    }

                    delegator.useShapeTestWriter(operation, (writer) -> {
                        LOGGER.fine(() -> format("Generating response error protocol test case for %s",
                                operation.getId()));
                        responseErrorTestBuilder.model(model)
                                .symbolProvider(symbolProvider)
                                .service(service)
                                .operation(operation)
                                .error(error)
                                .testCases(trait.getTestCases())
                                .build()
                                .generateTestFunction(writer);
                    });

                    delegator.useShapeTestWriter(operation, (writer) -> {
                        LOGGER.fine(() -> format("Generating response error protocol benchmark for %s",
                                operation.getId()));
                        responseErrorTestBuilder.model(model)
                                .symbolProvider(symbolProvider)
                                .service(service)
                                .operation(operation)
                                .error(error)
                                .testCases(trait.getTestCases())
                                .build()
                                .generateBenchmarkFunction(writer);
                    });
                });
            }
        }

        if (hasSerdBenchmarks[0]) {
            generateSerdBenchmarkHelpers();
        }
    }

    private void generateSerdBenchmarkHelpers() {
        delegator.useFileWriter("serd_benchmark_test.go", settings.getModuleName(), writer -> {
            writer.addUseImports(SmithyGoDependency.JSON);
            writer.addUseImports(SmithyGoDependency.OS);
            writer.addUseImports(SmithyGoDependency.FMT);
            writer.addUseImports(SmithyGoDependency.SYNC);
            writer.addUseImports(SmithyGoDependency.TESTING);
            writer.addUseImports(SmithyGoDependency.PATH_FILEPATH);
            writer.addUseImports(SmithyGoDependency.stdlib("runtime"));
            writer.write("""
                    type serdBenchmarkResult struct {
                        ID     string  `json:"id"`
                        N      int     `json:"n"`
                        Mean   float64 `json:"mean"`
                        P50    float64 `json:"p50"`
                        P90    float64 `json:"p90"`
                        P95    float64 `json:"p95"`
                        P99    float64 `json:"p99"`
                        StdDev float64 `json:"std_dev"`
                    }

                    type serdBenchmarkOutput struct {
                        Metadata       serdBenchmarkMetadata `json:"metadata"`
                        SerdBenchmarks []serdBenchmarkResult `json:"serde_benchmarks"`
                    }

                    type serdBenchmarkMetadata struct {
                        Lang      string     `json:"lang"`
                        Software  [][]string `json:"software"`
                        OS        string     `json:"os"`
                        Instance  string     `json:"instance"`
                        Precision string     `json:"precision"`
                    }

                    var (
                        serdBenchmarkResults []serdBenchmarkResult
                        serdBenchmarkMu      sync.Mutex
                    )

                    func addSerdBenchmarkResult(r serdBenchmarkResult) {
                        serdBenchmarkMu.Lock()
                        defer serdBenchmarkMu.Unlock()
                        serdBenchmarkResults = append(serdBenchmarkResults, r)
                    }

                    func writeSerdBenchmarkResults() error {
                        serdBenchmarkMu.Lock()
                        defer serdBenchmarkMu.Unlock()
                        if len(serdBenchmarkResults) == 0 {
                            return nil
                        }
                        output := serdBenchmarkOutput{
                            Metadata: serdBenchmarkMetadata{
                                Lang:      "Go",
                                Software:  [][]string{{"smithy-go", goModuleVersion}, {"AWS SDK for Go v2", goModuleVersion}},
                                OS:        runtime.GOOS + "/" + runtime.GOARCH,
                                Precision: "-9",
                            },
                            SerdBenchmarks: serdBenchmarkResults,
                        }
                        data, err := json.MarshalIndent(output, "", "  ")
                        if err != nil {
                            return fmt.Errorf("failed to marshal serd benchmark results: %v", err)
                        }
                        dir, err := findBenchmarkDir()
                        if err != nil {
                            return fmt.Errorf("failed to find benchmark directory: %v", err)
                        }
                        path := filepath.Join(dir, "benchmark.json")
                        return os.WriteFile(path, data, 0644)
                    }

                    func findBenchmarkDir() (string, error) {
                        dir, err := os.Getwd()
                        if err != nil {
                            return "", err
                        }
                        return dir, nil
                    }

                    func TestMain(m *testing.M) {
                        code := m.Run()
                        if err := writeSerdBenchmarkResults(); err != nil {
                            fmt.Fprintf(os.Stderr, "failed to write serd benchmark results: %v\\n", err)
                        }
                        os.Exit(code)
                    }
                    """);
        });
    }

    private <T extends HttpMessageTestCase> List<T> filterProtocolTestCases(List<T> testCases) {
        List<T> filtered = new ArrayList<>();
        for (T testCase : testCases) {
            if (testCase.getProtocol().equals(settings.getProtocol())) {
                filtered.add(testCase);
            }
        }
        return filtered;
    }

    private <T extends HttpMessageTestCase> List<T> filterSerdBenchmarkTaggedTestCases(List<T> testCases) {
        List<T> filtered = new ArrayList<>();
        for (T testCase : testCases) {
            if (testCase.hasTag(SERDE_BENCHMARK_TAG)) {
                filtered.add(testCase);
            }
        }
        return filtered;
    }
}