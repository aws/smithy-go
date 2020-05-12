/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import static java.lang.String.format;

import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator.GenerationContext;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.protocoltests.traits.HttpMessageTestCase;
import software.amazon.smithy.protocoltests.traits.HttpRequestTestsTrait;
import software.amazon.smithy.protocoltests.traits.HttpResponseTestsTrait;
import software.amazon.smithy.utils.IoUtils;

/**
 * Generates protocol unit tests for the HTTP protocol from smithy models.
 */
public class HttpProtocolTestGenerator {
    private static final Logger LOGGER = Logger.getLogger(HttpProtocolTestGenerator.class.getName());

    private final SymbolProvider symbolProvider;
    private final GoSettings settings;
    private final GoWriter writer;

    private final Model model;
    private final ServiceShape service;
    private final String protocolName;

    private final Set<String> additionalStubs = new TreeSet<>();

    private final HttpProtocolUnitTestRequestGenerator.Builder requestTestBuilder;
    private final HttpProtocolUnitTestResponseGenerator.Builder responseTestBuilder;
    private final HttpProtocolUnitTestResponseErrorGenerator.Builder responseErrorTestBuilder;

    /**
     * Initializes the protocol generator.
     *
     * @param context                  Protocol generation context.
     * @param requestTestBuilder       builder that will create a request test generator.
     * @param responseTestBuilder      build that will create a response test generator.
     * @param responseErrorTestBuilder builder that will create a response API error test generator.
     */
    public HttpProtocolTestGenerator(
            GenerationContext context,
            HttpProtocolUnitTestRequestGenerator.Builder requestTestBuilder,
            HttpProtocolUnitTestResponseGenerator.Builder responseTestBuilder,
            HttpProtocolUnitTestResponseErrorGenerator.Builder responseErrorTestBuilder
    ) {
        this.settings = context.getSettings();
        this.model = context.getModel();
        this.service = context.getService();
        this.protocolName = context.getProtocolName();
        this.symbolProvider = context.getSymbolProvider();
        this.writer = context.getWriter();

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
                // TODO filter test cases for this given protocol.
                requestTestBuilder.build(model, symbolProvider, protocolName, operation,
                        trait.getTestCases()).generateTestFunction(writer);
            });

            // 2. Generate test cases for each response.
            operation.getTrait(HttpResponseTestsTrait.class).ifPresent(trait -> {
                // TODO filter test cases for this given protocol.
                responseTestBuilder.build(model, symbolProvider, protocolName, operation,
                        trait.getTestCases()).generateTestFunction(writer);
            });

            // 3. Generate test cases for each error on each operation.
            for (StructureShape error : operationIndex.getErrors(operation)) {
                if (error.hasTag("server-only")) {
                    continue;
                }

                error.getTrait(HttpResponseTestsTrait.class).ifPresent(trait -> {
                    // TODO filter test cases for this given protocol.
                    responseErrorTestBuilder.build(model, symbolProvider, protocolName, operation, error,
                            trait.getTestCases()).generateTestFunction(writer);
                });
            }
        }

        // Include any additional stubs required.
        for (String additionalStub : additionalStubs) {
            writer.write(IoUtils.readUtf8Resource(getClass(), additionalStub));
        }
    }

    // Only generate test cases when its protocol matches the target protocol.
    private <T extends HttpMessageTestCase> void onlyIfProtocolMatches(T testCase, Runnable runnable) {
        if (testCase.getProtocol().equals(settings.getProtocol())) {
            LOGGER.fine(() -> format("Generating protocol test case for %s.%s", service.getId(), testCase.getId()));
            runnable.run();
        }
    }
}
