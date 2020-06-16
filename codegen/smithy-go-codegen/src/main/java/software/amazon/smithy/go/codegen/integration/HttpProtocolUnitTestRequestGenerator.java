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
import software.amazon.smithy.protocoltests.traits.HttpRequestTestCase;

/**
 * Generates HTTP protocol unit tests for HTTP request test cases.
 */
public class HttpProtocolUnitTestRequestGenerator extends HttpProtocolUnitTestGenerator<HttpRequestTestCase> {
    private static final Logger LOGGER = Logger.getLogger(HttpProtocolUnitTestRequestGenerator.class.getName());

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
        public HttpProtocolUnitTestRequestGenerator build(
                Model model, SymbolProvider symbolProvider, String protocolName, OperationShape operation,
                List<HttpRequestTestCase> testCases
        ) {
            return new HttpProtocolUnitTestRequestGenerator(model, symbolProvider, protocolName, operation, testCases);
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
    protected HttpProtocolUnitTestRequestGenerator(
            Model model, SymbolProvider symbolProvider, String protocolName, OperationShape operation,
            List<HttpRequestTestCase> testCases
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
        return "TestClient_$L_$LSerialize";
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
        writer.write("Params $P", inputSymbol);
        // TODO authScheme

        writer.write("ExpectMethod string");
        writer.write("ExpectURIPath string");

        writer.addUseImports(SmithyGoDependency.SMITHY_TESTING);
        writer.write("ExpectQuery []smithytesting.QueryItem");
        writer.write("RequireQuery []string");
        writer.write("ForbidQuery []string");

        writer.addUseImports(SmithyGoDependency.NET_HTTP);
        writer.write("ExpectHeader http.Header");
        writer.write("RequireHeader []string");
        writer.write("ForbidHeader []string");

        writer.write("BodyMediaType string");
        writer.write("BodyAssert func(io.Reader) error");
    }

    /**
     * Hook to generate all the test case parameters as struct member values for a single test case.
     * Must generate all test case parameter values before returning.
     *
     * @param writer   writer to write generated code with.
     * @param testCase definition of a single test case.
     */
    @Override
    protected void generateTestCaseValues(GoWriter writer, HttpRequestTestCase testCase) {
        writeStructField(writer, "Params", inputShape, testCase.getParams());

        writeStructField(writer, "ExpectMethod", "$S", testCase.getMethod());
        writeStructField(writer, "ExpectURIPath", "$S", testCase.getUri());

        writeQueryItemsStructField(writer, "ExpectQuery", testCase.getQueryParams());
        writeStringSliceStructField(writer, "RequireQuery", testCase.getRequireQueryParams());
        writeStringSliceStructField(writer, "ForbidQuery", testCase.getForbidQueryParams());

        writeHeaderStructField(writer, "ExpectHeader", testCase.getHeaders());
        writeStringSliceStructField(writer, "RequireHeader", testCase.getRequireHeaders());
        writeStringSliceStructField(writer, "ForbidHeader", testCase.getForbidHeaders());

        String bodyMediaType = "";
        if (testCase.getBodyMediaType().isPresent()) {
            bodyMediaType = testCase.getBodyMediaType().get();
            writeStructField(writer, "BodyMediaType", "$S", bodyMediaType);
        }
        if (testCase.getBody().isPresent()) {
            String body = testCase.getBody().get();

            writer.addUseImports(SmithyGoDependency.SMITHY_TESTING);
            writer.addUseImports(SmithyGoDependency.IO);
            if (body.length() == 0) {
                writeStructField(writer, "BodyAssert", "func(actual io.Reader) error {\n"
                        + "   return smithytesting.CompareReaderEmpty(actual)\n"
                        + "}");
            } else {
                String compareFunc = "";
                switch (bodyMediaType.toLowerCase()) {
                    case "application/json":
                        compareFunc = String.format("return smithytesting.CompareJSONReaderBytes(actual, []byte(`%s`))",
                                body);
                        break;
                    case "application/xml":
                        compareFunc = String.format("return smithytesting.CompareXMLReaderBytes(actual, []byte(`%s`))",
                                body);
                        break;
                    case "application/x-www-form-urlencoded":
                        compareFunc = String.format(
                                "return smithytesting.CompareURLFormReaderBytes(actual, []byte(`%s`))", body);
                        break;
                    default:
                        compareFunc = String.format("return smithytesting.CompareReaderBytes(actual, []byte(`%s`))",
                                body);
                        break;

                }
                writeStructField(writer, "BodyAssert", "func(actual io.Reader) error {\n $L \n}", compareFunc);
            }
        }
    }

    /**
     * Hook to generate the body of the test that will be invoked for all test cases of this operation. Should not
     * do any assertions.
     *
     * @param writer writer to write generated code with.
     */
    @Override
    protected void generateTestBody(GoWriter writer) {
        writer.addUseImports(SmithyGoDependency.NET_HTTP);
        writer.addUseImports(SmithyGoDependency.STRINGS);
        writer.addUseImports(SmithyGoDependency.IOUTIL);
        writer.write("var actualReq *http.Request");
        writeClientInit(writer, () -> {
            writer.write("actualReq = r");
            writer.openBlock("return &http.Response{", "}, nil", () -> {
                writeStructField(writer, "StatusCode", "200");
                writeStructField(writer, "Header", "http.Header{}");
                writeStructField(writer, "Body", "ioutil.NopCloser(strings.NewReader(\"\"))");
            });
        });

        writer.addUseImports(SmithyGoDependency.CONTEXT);
        writer.write("result, err := client.$L(context.Background(), c.Params)", opSymbol.getName());
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

        writeAssertScalarEqual(writer, "c.ExpectMethod", "actualReq.Method", "method");
        writeAssertScalarEqual(writer, "c.ExpectURIPath", "actualReq.URL.Path", "path");

        writeQueryItemBreakout(writer, "actualReq.URL.RawQuery", "queryItems");
        writeAssertHasQuery(writer, "c.ExpectQuery", "queryItems");
        writeAssertRequireQuery(writer, "c.RequireQuery", "queryItems");
        writeAssertForbidQuery(writer, "c.ForbidQuery", "queryItems");

        writeAssertHasHeader(writer, "c.ExpectHeader", "actualReq.Header");
        writeAssertRequireHeader(writer, "c.RequireHeader", "actualReq.Header");
        writeAssertForbidHeader(writer, "c.ForbidHeader", "actualReq.Header");

        writer.addUseImports(SmithyGoDependency.BYTES);
        writer.addUseImports(SmithyGoDependency.IO);
        writer.openBlock("if actualReq.Body != nil {", "}", () -> {
            writer.write("defer actualReq.Body.Close()");
        });
        writer.openBlock("if c.BodyAssert != nil {", "}", () -> {
            writer.openBlock("if err := c.BodyAssert(actualReq.Body); err != nil {", "}", () -> {
                writer.write("t.Errorf(\"expect body equal, got %v\", err)");
            });
        });
    }
}
