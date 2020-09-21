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

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator.GenerationContext;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.knowledge.HttpBindingIndex;
import software.amazon.smithy.model.pattern.SmithyPattern;
import software.amazon.smithy.model.pattern.SmithyPattern.Segment;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.EndpointTrait;

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
     static Set<StructureShape> generateErrorDispatcher(
            GenerationContext context,
            OperationShape operation,
            Symbol responseType,
            Consumer<GenerationContext> errorMessageCodeGenerator
    ) {
        GoWriter writer = context.getWriter();
        Set<StructureShape> errorShapes = new TreeSet<>();

        String errorFunctionName = ProtocolGenerator.getOperationErrorDeserFunctionName(
                operation, context.getProtocolName());

        writer.openBlock("func $L(response $P) (metadata interface{}, err error) {", "}",
                errorFunctionName, responseType, () -> {
            writer.addUseImports(SmithyGoDependency.BYTES);
            writer.addUseImports(SmithyGoDependency.IO);

            // Copy the response body into a seekable type
            writer.write("var errorBuffer bytes.Buffer");
            writer.openBlock("if _, err := io.Copy(&errorBuffer, response.Body); err != nil {", "}", () -> {
                writer.write("return metadata, &smithy.DeserializationError{Err: fmt.Errorf("
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

            writer.openBlock("switch {", "}", () -> {
                new TreeSet<>(operation.getErrors()).forEach(errorId -> {
                    StructureShape error = context.getModel().expectShape(errorId).asStructureShape().get();
                    errorShapes.add(error);
                    String errorDeserFunctionName = ProtocolGenerator.getErrorDeserFunctionName(
                            error, context.getProtocolName());
                    writer.addUseImports(SmithyGoDependency.STRINGS);
                    writer.openBlock("case strings.EqualFold($S, errorCode):", "", errorId.getName(), () -> {
                        writer.write("return metadata, $L(response, errorBody)", errorDeserFunctionName);
                    });
                });

                // Create a generic error
                writer.addUseImports(SmithyGoDependency.SMITHY);
                writer.openBlock("default:", "", () -> {
                    writer.openBlock("genericError := &smithy.GenericAPIError{", "}", () -> {
                        writer.write("Code: errorCode,");
                        writer.write("Message: errorMessage,");
                    });
                    writer.write("return metadata, genericError");
                });
            });
        });
        writer.write("");

        return errorShapes;
    }

    /**
     * Returns whether a shape has response bindings for the provided HttpBinding location.
     * The shape can be an operation shape, error shape or an output shape.
     *
     * @param model    the model
     * @param shape    the shape with possible presence of response bindings
     * @param location the HttpBinding location for response binding
     * @return boolean indicating presence of response bindings in the shape for provided location
     */
    public static boolean isShapeWithResponseBindings(Model model, Shape shape, HttpBinding.Location location) {
        Collection<HttpBinding> bindings = model.getKnowledge(HttpBindingIndex.class)
                .getResponseBindings(shape).values();

        for (HttpBinding binding : bindings) {
            if (binding.getLocation() == location) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sets the HostPrefix on the request if the operation has the Endpoint trait.
     *
     * <p>If there are no HostLabels then this will be a simple string assignment,
     * otherwise a string builder will be used.
     *
     * <p>This assumes that the smithyhttp.Request is available under the variable
     * "request" and the operation's input struct is available under the variable
     * "input".
     *
     * @param context The generation context.
     * @param operation The operation to set the host prefix for.
     */
    public static void setHostPrefix(GenerationContext context, OperationShape operation) {
        if (!operation.hasTrait(EndpointTrait.ID)) {
            return;
        }
        GoWriter writer = context.getWriter();
        SmithyPattern pattern = operation.expectTrait(EndpointTrait.class).getHostPrefix();

        // If the pattern is just a string without any labels, then we simply use string
        // assignment to avoid unnecessary imports / work.
        if (pattern.getLabels().isEmpty()) {
            writer.write("request.HostPrefix = $S", pattern.toString());
            return;
        }

        SymbolProvider symbolProvider = context.getSymbolProvider();
        StructureShape input = ProtocolUtils.expectInput(context.getModel(), operation);

        // If the pattern has labels, we need to build up the host prefix using a string builder.
        writer.addUseImports(SmithyGoDependency.STRINGS);
        writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_TRANSPORT);
        writer.addUseImports(SmithyGoDependency.FMT);
        writer.write("var prefix strings.Builder");
        for (Segment segment : pattern.getSegments()) {
            if (!segment.isLabel()) {
                writer.write("prefix.WriteString($S)", segment.toString());
            } else {
                MemberShape member = input.getMember(segment.getContent()).get();
                String memberName = symbolProvider.toMemberName(member);
                String memberReference = "input." + memberName;

                // Theoretically this should never be nil by this point unless validation has been disabled.
                writer.write("if $L == nil {", memberReference).indent();
                writer.write("return out, metadata, &smithy.SerializationError{Err: "
                        + "fmt.Errorf(\"$L forms part of the endpoint host and so may not be nil\")}", memberName);
                writer.dedent().write("} else if !smithyhttp.ValidateHostLabel(*$L) {", memberReference).indent();
                writer.write("return out, metadata, &smithy.SerializationError{Err: "
                        + "fmt.Errorf(\"$L forms part of the endpoint host and so must match \\\"[a-zA-Z0-9-]{1,63}\\\""
                        + ", but was \\\"%s\\\"\", *$L)}", memberName, memberReference);
                writer.dedent().openBlock("} else {", "}", () -> {
                    writer.write("prefix.WriteString(*$L)", memberReference);
                });
            }
        }

        writer.write("request.HostPrefix = prefix.String()");
    }
}
