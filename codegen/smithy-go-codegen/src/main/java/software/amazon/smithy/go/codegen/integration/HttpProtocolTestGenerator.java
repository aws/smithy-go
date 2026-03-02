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
            });

            // 2. Generate test cases for each response.
            operation.getTrait(HttpResponseTestsTrait.class).ifPresent(trait -> {
                final List<HttpResponseTestCase> testCases = filterProtocolTestCases(trait.getTestCases());
                if (testCases.isEmpty()) {
                    return;
                }

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
                });
            }
        }
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
}
