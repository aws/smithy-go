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
     * Initializes the protocol test generator.
     *
     * @param builder the builder initializing the generator.
     */
    protected HttpProtocolUnitTestResponseGenerator(Builder builder) {
        super(builder);
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

    public static class Builder extends HttpProtocolUnitTestGenerator.Builder<HttpResponseTestCase> {
        @Override
        public Builder model(Model model) {
            this.model = model;
            return this;
        }

        @Override
        public Builder symbolProvider(SymbolProvider symbolProvider) {
            this.symbolProvider = symbolProvider;
            return this;
        }

        @Override
        public Builder protocolName(String protocolName) {
            this.protocolName = protocolName;
            return this;
        }

        @Override
        public Builder operation(OperationShape operation) {
            this.operation = operation;
            return this;
        }

        @Override
        public Builder testCases(List<HttpResponseTestCase> testCases) {
            this.testCases = testCases;
            return this;
        }

        @Override
        public HttpProtocolUnitTestResponseGenerator build() {
            return new HttpProtocolUnitTestResponseGenerator(this);
        }
    }
}
