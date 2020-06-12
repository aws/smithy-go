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
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;

public abstract class HttpRpcProtocolGenerator implements ProtocolGenerator {

    private final boolean isErrorCodeInBody;
    private final Set<Shape> serializingDocumentShapes = new TreeSet<>();

    /**
     * Creates a Http RPC protocol generator.
     *
     * @param isErrorCodeInBody A boolean that indicates if the error code for the implementing protocol is located in
     *   the error response body, meaning this generator will parse the body before attempting to load an error code.
     */
    public HttpRpcProtocolGenerator(boolean isErrorCodeInBody) {
        this.isErrorCodeInBody = isErrorCodeInBody;
    }

    @Override
    public ApplicationProtocol getApplicationProtocol() {
        return ApplicationProtocol.createDefaultHttpApplicationProtocol();
    }

    /**
     * Gets the content-type for a request body.
     *
     * @return Returns the default content-type.
     */
    protected abstract String getDocumentContentType();

    @Override
    public void generateSharedSerializerComponents(GenerationContext context) {
        serializingDocumentShapes.addAll(ProtocolUtils.resolveRequiredDocumentShapeSerde(
                context.getModel(), serializingDocumentShapes));
        generateDocumentBodyShapeSerializers(context, serializingDocumentShapes);
    }

    /**
     * Generates serialization functions for shapes in the passed set. These functions
     * should return a value that can then be serialized by the implementation of
     * {@code serializeInputDocument}.
     *
     * @param context The generation context.
     * @param shapes  The shapes to generate serialization for.
     */
    protected abstract void generateDocumentBodyShapeSerializers(GenerationContext context, Set<Shape> shapes);

    @Override
    public void generateRequestSerializers(GenerationContext context) {
        TopDownIndex topDownIndex = context.getModel().getKnowledge(TopDownIndex.class);

        Set<OperationShape> containedOperations = new TreeSet<>(
                topDownIndex.getContainedOperations(context.getService()));
        for (OperationShape operation : containedOperations) {
            generateOperationSerializer(context, operation);
        }
    }

    private void generateOperationSerializer(GenerationContext context, OperationShape operation) {
        GoStackStepMiddlewareGenerator middleware = GoStackStepMiddlewareGenerator.createSerializeStepMiddleware(
                ProtocolGenerator.getSerializeMiddlewareName(operation.getId(), getProtocolName()));

        SymbolProvider symbolProvider = context.getSymbolProvider();
        Model model = context.getModel();
        Shape inputShape = ProtocolUtils.expectInput(model, operation);
        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);
        ApplicationProtocol applicationProtocol = getApplicationProtocol();
        Symbol requestType = applicationProtocol.getRequestType();

        middleware.writeMiddleware(context.getWriter(), (generator, writer) -> {
            writer.addUseImports(SmithyGoDependency.SMITHY);
            writer.addUseImports(SmithyGoDependency.FMT);
            writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_BINDING);

            // Cast the input request to the transport request type and check for errors.
            writer.write("request, ok := in.Request.($P)", requestType);
            writer.openBlock("if !ok {", "}", () -> {
                writer.write("return out, metadata, "
                        + "&smithy.SerializationError{Err: fmt.Errorf(\"unknown transport type %T\", in.Request)}");
            }).write("");

            // Cast the input parameters to the operation request type and check for errors.
            writer.write("input, ok := in.Parameters.($P)", inputSymbol);
            writer.openBlock("if !ok {", "}", () -> {
                writer.write("return out, metadata, "
                        + "&smithy.SerializationError{Err: fmt.Errorf(\"unknown input parameters type %T\","
                        + " in.Parameters)}");
            }).write("");

            writer.write("request.Request.URL.Path = $S", getOperationPath(context, operation));
            writer.write("request.Request.Method = \"POST\"");
            writer.write("httpBindingEncoder, err := httpbinding.NewEncoder(request.URL.Path, "
                    +  "request.URL.RawQuery, request.Header)");
            writer.openBlock("if err != nil {", "}", () -> {
                writer.write("return out, metadata, &smithy.SerializationError{Err: err}");
            });
            writeRequestHeaders(context, operation, writer);
            writer.write("");

            // delegate the setup and usage of the document serializer function for the protocol
            serializeInputDocument(model, symbolProvider, operation, generator, writer);
            serializingDocumentShapes.add(ProtocolUtils.expectInput(model, operation));
            writer.write("");

            writer.openBlock("if request.Request, err = httpBindingEncoder.Encode(request.Request); err != nil {",
                    "}", () -> {
                writer.write("return out, metadata, &smithy.SerializationError{Err: err}");
            });
            // Ensure the request value is updated if modified for a document.
            writer.write("in.Request = request");

            writer.write("");
            writer.write("return next.$L(ctx, in)", generator.getHandleMethodName());
        });
    }

    private void writeRequestHeaders(GenerationContext context, OperationShape operation, GoWriter writer) {
        writer.write("httpBindingEncoder.SetHeader(\"Content-Type\").String($S)", getDocumentContentType());
        writeDefaultHeaders(context, operation, writer);
    }

    /**
     * Writes any additional HTTP headers required by the protocol implementation.
     *
     * <p>Four parameters will be available in scope:
     * <ul>
     *   <li>{@code input: <T>}: the type generated for the operation's input.</li>
     *   <li>{@code request: smithyhttp.HTTPRequest}: the HTTP request that will be sent.</li>
     *   <li>{@code httpBindingEncoder: httpbinding.Encoder}: the HTTP encoder to use to set the headers.</li>
     *   <li>{@code ctx: context.Context}: a type containing context and tools for type serde.</li>
     * </ul>
     *
     * @param context The generation context.
     * @param operation The operation being generated.
     * @param writer The writer to use.
     */
    protected void writeDefaultHeaders(GenerationContext context, OperationShape operation, GoWriter writer) {}

    /**
     * Provides the request path for the operation.
     *
     * @param context The generation context.
     * @param operation The operation being generated.
     * @return The path to send HTTP requests to.
     */
    protected abstract String getOperationPath(GenerationContext context, OperationShape operation);

    /**
     * Generate the document serializer logic for the serializer middleware body.
     *
     * <p>Three parameters will be available in scope:
     * <ul>
     *   <li>{@code input: <T>}: the type generated for the operation's input.</li>
     *   <li>{@code request: smithyhttp.HTTPRequest}: the HTTP request that will be sent.</li>
     *   <li>{@code ctx: context.Context}: a type containing context and tools for type serde.</li>
     * </ul>
     *
     * @param model          the model
     * @param symbolProvider the symbol provider
     * @param operation      the operation
     * @param generator      middleware generator definition
     * @param writer         the writer within the middleware context
     */
    protected abstract void serializeInputDocument(
            Model model,
            SymbolProvider symbolProvider,
            OperationShape operation,
            GoStackStepMiddlewareGenerator generator,
            GoWriter writer
    );

    @Override
    public void generateResponseDeserializers(GenerationContext context) {
        TopDownIndex topDownIndex = context.getModel().getKnowledge(TopDownIndex.class);
        Set<OperationShape> containedOperations = new TreeSet<>(
                topDownIndex.getContainedOperations(context.getService()));
        for (OperationShape operation : containedOperations) {
            generateOperationDeserializer(context, operation);
        }
    }

    private void generateOperationDeserializer(GenerationContext context, OperationShape operation) {
        GoStackStepMiddlewareGenerator middleware = GoStackStepMiddlewareGenerator.createDeserializeStepMiddleware(
                ProtocolGenerator.getDeserializeMiddlewareName(operation.getId(), getProtocolName()));
        StructureShape outputShape = ProtocolUtils.expectOutput(context.getModel(), operation);
        middleware.writeMiddleware(context.getWriter(), (generator, writer) -> {
            // TODO: actually implement this
            writer.write("out, metadata, err = next.$L(ctx, in)", generator.getHandleMethodName());
            writer.write("if err != nil { return out, metadata, err }");
            writer.write("");

            writer.write("output := &$T{}", context.getSymbolProvider().toSymbol(outputShape));
            writer.write("out.Result = output");
            writer.write("");
            writer.write("return out, metadata, err");
        });
    }
}
