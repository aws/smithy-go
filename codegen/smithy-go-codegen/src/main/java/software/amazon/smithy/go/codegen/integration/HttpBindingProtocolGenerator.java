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

import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.*;
import software.amazon.smithy.model.traits.*;
import software.amazon.smithy.model.traits.TimestampFormatTrait.Format;
import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.utils.OptionalUtils;

import java.util.*;
import java.util.logging.Logger;

/**
 * Abstract implementation useful for all protocols that use HTTP bindings.
 */
//TODO: check ending ;
public abstract class HttpBindingProtocolGenerator implements ProtocolGenerator {

    private static final Logger LOGGER = Logger.getLogger(HttpBindingProtocolGenerator.class.getName());

    private final Set<Shape> serializingDocumentShapes = new TreeSet<>();
    private final Set<Shape> deserializingDocumentShapes = new TreeSet<>();
    private final Set<StructureShape> deserializingErrorShapes = new TreeSet<>();
    private final Set<StructureShape> serializeEventShapes = new TreeSet<>();
    private final Set<StructureShape> deserializingEventShapes = new TreeSet<>();
    private final Set<UnionShape> serializeEventUnions = new TreeSet<>();
    private final Set<UnionShape> deserializeEventUnions = new TreeSet<>();
    private final boolean isErrorCodeInBody;

    /**
     * Creates a Http binding protocol generator.
     *
     * @param isErrorCodeInBody A boolean that indicates if the error code for the implementing protocol is located in
     *   the error response body, meaning this generator will parse the body before attempting to load an error code.
     */
    public HttpBindingProtocolGenerator(boolean isErrorCodeInBody) {
        this.isErrorCodeInBody = isErrorCodeInBody;
    }

    @Override
    public ApplicationProtocol getApplicationProtocol() {
        return new ApplicationProtocol("http", null, null, null);
    }

    /**
     * Gets the default serde format for timestamps.
     *
     * @return Returns the default format.
     */
    protected abstract Format getDocumentTimestampFormat();

    /**
     * Gets the default content-type when a document is synthesized in the body.
     *
     * @return Returns the default content-type.
     */
    protected abstract String getDocumentContentType();

    /**
     * Generates serialization functions for shapes in the passed set. These functions
     * should return a value that can then be serialized by the implementation of
     * {@code serializeInputDocument}. The {@link DocumentShapeSerVisitor} and {@link DocumentMemberSerVisitor}
     * are provided to reduce the effort of this implementation.
     *
     * @param context The generation context.
     * @param shapes The shapes to generate serialization for.
     */
    protected abstract void generateDocumentBodyShapeSerializers(GenerationContext context, Set<Shape> shapes);

    /**
     * Detects if the target shape is expressed as a native simple type.
     *
     * @param target The shape of the value being provided.
     * @return Returns if the shape is a native simple type.
     */
    private boolean isNativeSimpleType(Shape target) {
        return target instanceof BooleanShape || target instanceof DocumentShape
                       || target instanceof NumberShape || target instanceof StringShape;
    }

    @Override
    public void generateRequestSerializers(GenerationContext context) {
        TopDownIndex topDownIndex = context.getModel().getKnowledge(TopDownIndex.class);

        Set<OperationShape> containedOperations = new TreeSet<>(
                topDownIndex.getContainedOperations(context.getService()));
        for (OperationShape operation : containedOperations) {
            OptionalUtils.ifPresentOrElse(
                    operation.getTrait(HttpTrait.class),
                    httpTrait -> generateOperationSerializer(context, operation, httpTrait),
                    () -> LOGGER.warning(String.format(
                            "Unable to generate %s protocol request bindings for %s because it does not have an "
                            + "http binding trait", getProtocol(), operation.getId())));
        }
    }

    private void generateOperationSerializer(
            GenerationContext context,
            OperationShape operation,
            HttpTrait trait
    ) {
//        SymbolProvider symbolProvider = context.getSymbolProvider();
//        Symbol symbol = symbolProvider.toSymbol(operation);
//        SymbolReference requestType = getApplicationProtocol().getRequestType();
//        HttpBindingIndex bindingIndex = context.getModel().getKnowledge(HttpBindingIndex.class);
//        GoWriter writer = context.getWriter();
    }

    /**
     * Generates deserialization functions for shapes in the passed set. These functions
     * should return a value that can then be deserialized by the implementation of
     * {@code deserializeOutputDocument}. The {@link DocumentShapeDeserVisitor} and
     * {@link DocumentMemberDeserVisitor} are provided to reduce the effort of this implementation.
     *
     * @param context The generation context.
     * @param shapes The shapes to generate deserialization for.
     */
    protected abstract void generateDocumentBodyShapeDeserializers(GenerationContext context, Set<Shape> shapes);

    /**
     * Writes the code needed to serialize the input payload of a request.
     *
     * <p>Implementations of this method are expected to set a value to the
     * {@code body} variable that will be serialized as the request body.
     * This variable will already be defined in scope.
     *
     * @param context The generation context.
     * @param operation The operation being generated.
     * @param payloadBinding The payload binding to serialize.
     */
    protected void serializeInputPayload(
            GenerationContext context,
            OperationShape operation,
            HttpBinding payloadBinding
    ) {
//        GoWriter writer = context.getWriter();
//        SymbolProvider symbolProvider = context.getSymbolProvider();
//        String memberName = symbolProvider.toMemberName(payloadBinding.getMember());
    }

    /**
     * Writes any additional HTTP headers required by the protocol implementation.
     *
     * @param context The generation context.
     * @param operation The operation being generated.
     */
    protected void writeDefaultHeaders(GenerationContext context, OperationShape operation) {}

    /**
     * Writes the code that loads an {@code errorCode} String with the content used
     * to dispatch errors to specific serializers.
     *
     * @param context The generation context.
     */
    protected abstract void writeErrorCodeParser(GenerationContext context);

    /**
     * Writes the code needed to deserialize the output document of a response.
     *
     * @param context The generation context.
     * @param operationOrError The operation or error with a document being deserialized.
     * @param documentBindings The bindings to read from the document.
     */
    protected abstract void deserializeOutputDocument(
            GenerationContext context,
            Shape operationOrError,
            List<HttpBinding> documentBindings
    );

    /**
     * Writes the code needed to serialize the input document of a request.
     *
     * @param context The generation context.
     * @param operation The operation being generated.
     * @param documentBindings The bindings to place in the document.
     */
    protected abstract void serializeInputDocument(
            GenerationContext context,
            OperationShape operation,
            List<HttpBinding> documentBindings
    );

    @Override
    public void generateResponseDeserializers(GenerationContext context) {
        TopDownIndex topDownIndex = context.getModel().getKnowledge(TopDownIndex.class);

        Set<OperationShape> containedOperations = new TreeSet<>(
                topDownIndex.getContainedOperations(context.getService()));
        for (OperationShape operation : containedOperations) {
            OptionalUtils.ifPresentOrElse(
                    operation.getTrait(HttpTrait.class),
                    httpTrait -> generateOperationDeserializer(context, operation, httpTrait),
                    () -> LOGGER.warning(String.format(
                            "Unable to generate %s protocol response bindings for %s because it does not have an "
                                    + "http binding trait", getProtocol(), operation.getId())));
        }
    }

    private void generateOperationDeserializer(
            GenerationContext context,
            OperationShape operation,
            HttpTrait trait
    ) {
//        SymbolProvider symbolProvider = context.getSymbolProvider();
//        Symbol symbol = symbolProvider.toSymbol(operation);
//        SymbolReference responseType = getApplicationProtocol().getResponseType();
//        HttpBindingIndex bindingIndex = context.getModel().getKnowledge(HttpBindingIndex.class);
//        Model model = context.getModel();
//        GoWriter writer = context.getWriter();
    }
}
