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

import java.util.logging.Logger;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
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
    protected void generateTestServerHandler(GoWriter writer) {
        writer.openBlock("for k, vs := range c.Header {", "}", () -> {
            writer.openBlock("for _, v := range vs {", "}", () -> {
                writer.write("w.Header().Add(k, v)");
            });
        });

        writer.openBlock("if len(c.BodyMediaType) != 0 && len(w.Header().Values(\"Content-Type\")) == 0 {", "}", () -> {
            writer.write("w.Header().Set(\"Content-Type\", c.BodyMediaType)");
        });

        writer.openBlock("if len(c.Body) != 0 {", "}", () -> {
            writer.addUseImports(SmithyGoDependency.STRCONV);
            writer.write("w.Header().Set(\"Content-Length\", strconv.Itoa(len(c.Body)))");

            writer.addUseImports(SmithyGoDependency.IO);
            writer.addUseImports(SmithyGoDependency.BYTES);
            writer.openBlock("if _, err := io.Copy(w, bytes.NewReader(c.Body)); err != nil {", "}", () -> {
                writer.write("t.Errorf(\"failed to write response body, %v\", err)");
            });
        });
    }

    /**
     * Hook to generate the body of the test that will be invoked for all test cases of this operation. Should not
     * do any assertions.
     *
     * @param writer writer to write generated code with.
     */
    @Override
    protected void generateTestInvokeClientOperation(GoWriter writer, String clientName) {
        writer.addUseImports(SmithyGoDependency.CONTEXT);
        writer.write("var params $T", inputSymbol);
        writer.write("result, err := $L.$L(context.Background(), &params)", clientName, opSymbol.getName());
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
        public HttpProtocolUnitTestResponseGenerator build() {
            return new HttpProtocolUnitTestResponseGenerator(this);
        }
    }
}
