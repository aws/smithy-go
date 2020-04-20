/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.SymbolReference;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.go.codegen.ApplicationProtocol;

import java.util.Set;
import java.util.TreeSet;

/**
 * Abstract implementation useful for all HTTP protocols without bindings.
 */
public abstract class HttpRpcProtocolGenerator implements ProtocolGenerator {

    private final Set<Shape> serializingDocumentShapes = new TreeSet<>();

    @Override
    public ApplicationProtocol getApplicationProtocol() {
        return new ApplicationProtocol("http", null, null, null);
    }

    /**
     * Gets the content-type for a request body.
     *
     * @return Returns the default content-type.
     */
    protected abstract String getDocumentContentType();

    /**
     * Generates serialization functions for shapes in the passed set. These functions
     * should return a value that can then be serialized by the implementation of
     * {@code serializeInputDocument}. The {@link DocumentShapeSerVisitor} and
     * {@link DocumentMemberSerVisitor} are provided to reduce the effort of this implementation.
     *
     * @param context The generation context.
     * @param shapes The shapes to generate serialization for.
     */
    protected abstract void generateDocumentBodyShapeSerializers(GenerationContext context, Set<Shape> shapes);

    @Override
    public void generateSharedComponents(GenerationContext context) {
        generateDocumentBodyShapeSerializers(context, serializingDocumentShapes);

//        GoWriter writer = context.getWriter();
    }

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
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Symbol symbol = symbolProvider.toSymbol(operation);
        SymbolReference requestType = getApplicationProtocol().getRequestType();
//        GoWriter writer = context.getWriter();
    }

    private void writeRequestHeaders(GenerationContext context, OperationShape operation) {
    }

    private boolean writeRequestBody(GenerationContext context, OperationShape operation) {
        if (operation.getInput().isPresent()) {
            // If there's an input present, we know it's a structure.
            StructureShape inputShape = context.getModel().expectShape(operation.getInput().get())
                    .asStructureShape().get();

            // Track input shapes so their serializers may be generated.
            serializingDocumentShapes.add(inputShape);

            // Write the default `body` property.
//            context.getWriter().write("let body: any;");
            serializeInputDocument(context, operation, inputShape);
            return true;
        }

        return writeUndefinedInputBody(context, operation);
    }

    /**
     * Provides the request path for the operation.
     *
     * @param context The generation context.
     * @param operation The operation being generated.
     * @return The path to send HTTP requests to.
     */
    protected abstract String getOperationPath(GenerationContext context, OperationShape operation);

    /**
     * Writes any additional HTTP headers required by the protocol implementation.
     *
     * <p>Two parameters will be available in scope:
     * <ul>
     *   <li>{@code input: <T>}: the type generated for the operation's input.</li>
     *   <li>{@code context: SerdeContext}: a TypeScript type containing context and tools for type serde.</li>
     * </ul>
     *
     * @param context The generation context.
     * @param operation The operation being generated.
     */
    protected void writeDefaultHeaders(GenerationContext context, OperationShape operation) {}

    /**
     * Writes the code needed to serialize the input document of a request.
     *
     * <p>Implementations of this method are expected to set a value to the
     * {@code body} variable that will be serialized as the request body.
     * This variable will already be defined in scope.
     *
     * @param context The generation context.
     * @param operation The operation being generated.
     * @param inputStructure The structure containing the operation input.
     */
    protected abstract void serializeInputDocument(
            GenerationContext context,
            OperationShape operation,
            StructureShape inputStructure
    );

    /**
     * Writes any default body contents when an operation has an undefined input.
     *
     * <p>Implementations of this method are expected to set a value to the
     * {@code body} variable that will be serialized as the request body.
     * This variable will NOT be defined in scope and should be defined by
     * implementations if they wish to set it.
     *
     * <p>Implementations should return true if they define a body variable, and
     * false otherwise.
     *
     * @param context The generation context.
     * @param operation The operation being generated.
     * @return If a body variable was defined.
     */
    protected boolean writeUndefinedInputBody(GenerationContext context, OperationShape operation) {
        // Pass
        return false;
    }

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
//        SymbolProvider symbolProvider = context.getSymbolProvider();
//        Symbol symbol = symbolProvider.toSymbol(operation);
//        SymbolReference responseType = getApplicationProtocol().getResponseType();
//        GoWriter writer = context.getWriter();
    }
}
