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
 *
 *
 */

package software.amazon.smithy.go.codegen.integration;

import java.util.List;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.protocoltests.traits.HttpResponseTestCase;

/**
 * Generates HTTP protocol unit tests for HTTP response test cases.
 */
public class HttpProtocolUnitTestResponseGenerator extends HttpProtocolUnitTestGenerator<HttpResponseTestCase> {
    private static final Logger LOGGER = Logger.getLogger(HttpProtocolUnitTestResponseGenerator.class.getName());

    /**
     * Builder to lazy initialize the protocol test generator with operation specific configuration.
     */
    public static class Builder {
        /**
         * Builds the test generator for the provided configuration.
         *
         * @param model          Smithy model.
         * @param symbolProvider Smithy symbol provider.
         * @param protocolName   Name of the model's protocol that will be generated.
         * @param operation      shape for the Operation the unit test is generated for.
         * @param testCases      Set of test cases for the operation.
         * @return initialize protocol test generator.
         */
        public HttpProtocolUnitTestResponseGenerator build(
                Model model, SymbolProvider symbolProvider, String protocolName, OperationShape operation,
                List<HttpResponseTestCase> testCases
        ) {
            return new HttpProtocolUnitTestResponseGenerator(model, symbolProvider, protocolName, operation, testCases);
        }
    }

    /**
     * Initializes the protocol test generator.
     *
     * @param model          smithy model
     * @param symbolProvider symbol provider
     * @param protocolName   name of the protocol test is being generated for.
     * @param operation      operation shape the test is for.
     * @param testCases      test cases for the operation.
     */
    protected HttpProtocolUnitTestResponseGenerator(
            Model model, SymbolProvider symbolProvider, String protocolName, OperationShape operation,
            List<HttpResponseTestCase> testCases
    ) {
        super(model, symbolProvider, protocolName, operation, testCases);
    }

    /**
     * Provides the unit test function's format string.
     *
     * @return returns format string paired with unitTestFuncNameArgs
     */
    @Override
    protected String unitTestFuncNameFormat() {
        return "TestClient_$L_$LDeserialize";
    }

    /**
     * Provides the unit test function name's format string arguments.
     *
     * @return returns a list of arguments used to format the unitTestFuncNameFormat returned format string.
     */
    @Override
    protected Object[] unitTestFuncNameArgs() {
        return new Object[]{opSymbol.getName(), protocolName};
    }

    /**
     * Hook to generate the parameter declarations as struct parameters into the test case's struct definition.
     * Must generate all test case parameters before returning.
     *
     * @param writer writer to write generated code with.
     */
    @Override
    protected void generateTestCaseParams(GoWriter writer) {
        writer.write("StatusCode int");
        // TODO authScheme
        writer.addUseImports(SmithyGoDependency.NET_HTTP);

        writer.write("Header http.Header");
        // TODO why do these exist?
        // writer.write("RequireHeaders []string");
        // writer.write("ForbidHeaders []string");

        writer.write("BodyMediaType string");
        writer.write("Body []byte");

        writer.write("ExpectResult $P", outputSymbol);
    }

    /**
     * Hook to generate all the test case parameters as struct member values for a single test case.
     * Must generate all test case parameter values before returning.
     *
     * @param writer   writer to write generated code with.
     * @param testCase definition of a single test case.
     */
    @Override
    protected void generateTestCaseValues(GoWriter writer, HttpResponseTestCase testCase) {
        writeStructField(writer, "StatusCode", testCase.getCode());
        writeHeaderStructField(writer, "Header", testCase.getHeaders());

        testCase.getBodyMediaType().ifPresent(mediaType -> {
            writeStructField(writer, "BodyMediaType", "$S", mediaType);
        });
        testCase.getBody().ifPresent(body -> {
            writeStructField(writer, "Body", "[]byte(`$L`)", body);
        });

        writeStructField(writer, "ExpectResult", outputShape, testCase.getParams());
    }

    /**
     * Hook to generate the body of the test that will be invoked for all test cases of this operation. Should not
     * do any assertions.
     *
     * @param writer writer to write generated code with.
     */
    @Override
    protected void generateTestBody(GoWriter writer) {
        writeClientInit(writer, () -> {
            // TODO disable required parameter validation
            writer.addUseImports(SmithyGoDependency.IOUTIL);
            writer.openBlock("return &http.Response{", "}, nil", () -> {
                writeStructField(writer, "StatusCode", "c.StatusCode");
                writeStructField(writer, "Header", "c.Header.Clone()");

                // TODO move this into the Header value instead of a struct field.
                //writeStructField(writer, "ContentType", "c.BodyMediaType");
                writeStructField(writer, "Body", "ioutil.NopCloser(bytes.NewReader(c.Body))");
            });
        });

        writer.write("var params $T", inputSymbol);

        writer.addUseImports(SmithyGoDependency.CONTEXT);
        writer.write("result, err := client.$L(context.Background(), &params)", opSymbol.getName());
    }

    /**
     * Hook to generate the assertions for the operation's test cases. Will be in the same scope as the test body.
     *
     * @param writer writer to write generated code with.
     */
    @Override
    protected void generateTestAssertions(GoWriter writer) {
        writeAssertNil(writer, "err");
        writeAssertNotNil(writer, "result");

        writer.addUseImports(SmithyGoDependency.SMITHY_MIDDLEWARE);
        writeAssertComplexEqual(writer, "c.ExpectResult", "result", new String[]{"middleware.Metadata{}"});

        // TODO assertion for protocol metadata
    }
}
