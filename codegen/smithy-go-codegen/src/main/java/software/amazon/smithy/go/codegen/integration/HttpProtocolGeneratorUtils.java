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

import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator.GenerationContext;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.StructureShape;

public final class HttpProtocolGeneratorUtils {

    private HttpProtocolGeneratorUtils() {}

    /**
     * Generates a function that handles error deserialization by getting the error code then
     * dispatching to the error-specific deserializer.
     *
     * If the error code does not map to a known error, a generic error will be returned using
     * the error code and error message discovered in the response.
     *
     * The default error message and code are both "UnknownError".
     *
     * @param context The generation context.
     * @param operation The operation to generate for.
     * @param responseType The response type for the HTTP protocol.
     * @param errorMessageCodeGenerator A consumer that generates a snippet that sets the {@code errorCode}
     *                                  and {@code errorMessage} variables from the http response.
     * @return A set of all error structure shapes for the operation that were dispatched to.
     */
    public static Set<StructureShape> generateJsonErrorDispatcher(
            GenerationContext context,
            OperationShape operation,
            Symbol responseType,
            Consumer<GenerationContext> errorMessageCodeGenerator
    ) {
        GoWriter writer = context.getWriter();
        Set<StructureShape> errorShapes = new TreeSet<>();

        String errorFunctionName = ProtocolGenerator.getOperationErrorDeserFunctionName(
                operation, context.getProtocolName());

        writer.openBlock("func $L(response $P) error {", "}", errorFunctionName, responseType, () -> {
            writer.addUseImports(SmithyGoDependency.BYTES);
            writer.addUseImports(SmithyGoDependency.IO);

            // Copy the response body into a seekable type
            writer.write("var errorBuffer bytes.Buffer");
            writer.openBlock("if _, err := io.Copy(&errorBuffer, response.Body); err != nil {", "}", () -> {
                writer.write("return &smithy.DeserializationError{Err: fmt.Errorf("
                        + "\"failed to copy error response body, %w\", err)}");
            });
            writer.write("errorBody := bytes.NewReader(errorBuffer.Bytes())");
            writer.write("");

            // Set the default values for code and message.
            writer.write("errorCode := \"UnknownError\"");
            writer.write("errorMessage := errorCode");
            writer.write("");

            // Dispatch to the message/code generator to try to get the specific code and message.
            errorMessageCodeGenerator.accept(context);

            writer.openBlock("switch errorCode {", "}", () -> {
                new TreeSet<>(operation.getErrors()).forEach(errorId -> {
                    StructureShape error = context.getModel().expectShape(errorId).asStructureShape().get();
                    errorShapes.add(error);
                    String errorDeserFunctionName = ProtocolGenerator.getErrorDeserFunctionName(
                            error, context.getProtocolName());
                    writer.openBlock("case $S:", "", errorId.getName(), () -> {
                        writer.write("return $L(response, errorBody)", errorDeserFunctionName);
                    });
                });

                // Create a generic error
                writer.addUseImports(SmithyGoDependency.SMITHY);
                writer.openBlock("default:", "", () -> {
                    writer.openBlock("genericError := &smithy.GenericAPIError{", "}", () -> {
                        writer.write("Code: errorCode,");
                        writer.write("Message: errorMessage,");
                    });
                    writer.write("return genericError");
                });
            });
        });
        writer.write("");

        return errorShapes;
    }

    /**
     * Generates a function that handles error deserialization by getting the error code then
     * dispatching to the error-specific deserializer.
     *
     * If the error code does not map to a known error, a generic error will be returned using
     * the error code and error message discovered in the response.
     *
     * The default error message and code are both "UnknownError".
     *
     * @param context The generation context.
     * @param operation The operation to generate for.
     * @param responseType The response type for the HTTP protocol.
     * @param errorMessageCodeGenerator A consumer that generates a snippet that sets the {@code errorCode}
     *                                  and {@code errorMessage} variables from the http response.
     * @return A set of all error structure shapes for the operation that were dispatched to.
     */
    public static Set<StructureShape> generateXmlErrorDispatcher(
            GenerationContext context,
            OperationShape operation,
            Symbol responseType,
            Consumer<GenerationContext> errorMessageCodeGenerator
    ) {
        GoWriter writer = context.getWriter();
        Set<StructureShape> errorShapes = new TreeSet<>();

        String errorFunctionName = ProtocolGenerator.getOperationErrorDeserFunctionName(
                operation, context.getProtocolName());

        writer.addUseImports(SmithyGoDependency.SMITHY_DECODING);
        writer.addUseImports(SmithyGoDependency.IO);

        writer.openBlock("func $L(response $P) error {", "}",
                errorFunctionName, responseType, () -> {

            writer.addUseImports(SmithyGoDependency.BYTES);
            writer.addUseImports(SmithyGoDependency.IO);
            writer.write("defer response.Body.Close()");
            writer.write("");

            // Copy the response body into a seekable type
            writer.write("var errorBuffer bytes.Buffer");
            writer.openBlock("if _, err := io.Copy(&errorBuffer, response.Body); err != nil {", "}", () -> {
            writer.write("return &smithy.DeserializationError{Err: fmt.Errorf("
                         + "\"failed to copy error response body, %w\", err)}");
            });
            writer.write("errorBody := bytes.NewReader(errorBuffer.Bytes())");
            writer.insertTrailingNewline();

            // Dispatch to the message/code generator to try to get the specific code and message.
            errorMessageCodeGenerator.accept(context);

            writer.openBlock("switch errorCode {", "}", () -> {
                new TreeSet<>(operation.getErrors()).forEach(errorId -> {
                    StructureShape error = context.getModel().expectShape(errorId).asStructureShape().get();
                    errorShapes.add(error);
                    String errorDeserFunctionName = ProtocolGenerator.getErrorDeserFunctionName(
                            error, context.getProtocolName());
                    writer.openBlock("case $S:", "", errorId.getName(), () -> {
                        writer.addUseImports(SmithyGoDependency.XML);
                        initializeXmlDecoder(writer, "errorBody");
                        writer.openBlock("if err := $L(decoder, response); err != nil {",
                                "}", errorDeserFunctionName, () -> {
                            writer.openBlock("if de, ok := err.(*smithy.DeserializationError); ok {", "}",  () -> {
                                writer.addUseImports(SmithyGoDependency.BYTES);
                                writer.addUseImports(SmithyGoDependency.SMITHY);
                                writer.write("var snapshot bytes.Buffer");
                                writer.write("io.Copy(&snapshot, ringBuffer)");
                                writer.write("de.Snapshot = snapshot.Bytes()");
                                writer.write("return de");
                            });
                            writer.write("return err");
                        });
                        writer.write("return nil");
                    });
                });

                // Create a generic error
                writer.addUseImports(SmithyGoDependency.SMITHY);
                writer.openBlock("default:", "", () -> {
                    writer.openBlock("if len(errorCode) ==0 {", "}", () -> {
                        writer.write("errorCode = \"UnknownError\"");
                    });
                    writer.openBlock("genericError := &smithy.GenericAPIError{", "}", () -> {
                        writer.write("Code: errorCode,");
                        writer.write("Message: errorCode,");
                    });
                    writer.write("return genericError");
                });
            });
        });
        writer.insertTrailingNewline();

        return errorShapes;
    }

    // TODO: These should be rearranged. Keeping it here as not sure where these will go.
    //  Might be useful to rearrange when working with ec2 query or query deser.

    // initializeXmlDecoder generates stub code to initialize xml decoder
    private static void initializeXmlDecoder(GoWriter writer, String bodyLocation) {
        // Use a ring buffer and tee reader to help in pinpointing any deserialization errors.
        writer.addUseImports(SmithyGoDependency.SMITHY_IO);
        writer.write("buff := make([]byte, 1024)");
        writer.write("ringBuffer := smithyio.NewRingBuffer(buff)");
        writer.insertTrailingNewline();

        writer.addUseImports(SmithyGoDependency.IO);
        writer.addUseImports(SmithyGoDependency.XML);
        writer.addUseImports(SmithyGoDependency.SMITHY_DECODING);
        writer.write("body := io.TeeReader($L, ringBuffer)", bodyLocation);
        writer.write("rootDecoder := xml.NewDecoder(body)");
        writer.write("t, err := smithydecoding.FetchXmlRootElement(rootDecoder)");

        handleXmlDecodeError(writer, "error fetching the start element of xml response error body", "");

        writer.write("decoder := smithydecoding.NewXMLNodeDecoder(rootDecoder, t)");
        writer.insertTrailingNewline();
    }

    // handleXmlDecodeError handles the xml deserialization error wrapping
    private static void handleXmlDecodeError(GoWriter writer, String errorStatement, String returnExtras) {
        writer.addUseImports(SmithyGoDependency.IO);
        writer.openBlock("if err != nil && err != io.EOF {", "}", () -> {
            writer.addUseImports(SmithyGoDependency.BYTES);
            writer.addUseImports(SmithyGoDependency.SMITHY);
            writer.write("var snapshot bytes.Buffer");
            writer.write("io.Copy(&snapshot, ringBuffer)");
            writer.openBlock("return $L&smithy.DeserializationError {", "}", returnExtras, () -> {
                String st = "fmt.Errorf(\"" + errorStatement + "%w\",err)";
                writer.write(String.format("Err : %s,", st));
                writer.write("Snapshot: snapshot.Bytes(),");
            });
        }).write("");
    }
}
