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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.go.codegen.GoDependency;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.knowledge.HttpBindingIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.HttpTrait;
import software.amazon.smithy.model.traits.TimestampFormatTrait.Format;
import software.amazon.smithy.utils.OptionalUtils;


/**
 * Abstract implementation useful for all protocols that use HTTP bindings.
 */
public abstract class HttpBindingProtocolGenerator implements ProtocolGenerator {

    private static final Logger LOGGER = Logger.getLogger(HttpBindingProtocolGenerator.class.getName());

    private final boolean isErrorCodeInBody;

    /**
     * Creates a Http binding protocol generator.
     *
     * @param isErrorCodeInBody A boolean that indicates if the error code for the implementing protocol is located in
     *                          the error response body, meaning this generator will parse the body before attempting to
     *                          load an error code.
     */
    public HttpBindingProtocolGenerator(boolean isErrorCodeInBody) {
        this.isErrorCodeInBody = isErrorCodeInBody;
    }

    @Override
    public ApplicationProtocol getApplicationProtocol() {
        return ApplicationProtocol.createDefaultHttpApplicationProtocol();
    }

    @Override
    public void generateSharedComponents(GenerationContext context) {
        // pass
    }

    @Override
    public void generateRequestSerializers(GenerationContext context) {
        TopDownIndex topDownIndex = context.getModel().getKnowledge(TopDownIndex.class);

        Set<OperationShape> containedOperations = new TreeSet<>(
                topDownIndex.getContainedOperations(context.getService()));
        for (OperationShape operation : containedOperations) {
            OptionalUtils.ifPresentOrElse(
                    operation.getTrait(HttpTrait.class),
                    httpTrait -> generateOperationSerializer(context, operation),
                    () -> LOGGER.warning(String.format(
                            "Unable to generate %s protocol request bindings for %s because it does not have an "
                                    + "http binding trait", getProtocol(), operation.getId())));
        }
    }

    @Override
    public void generateResponseDeserializers(GenerationContext context) {
        // pass
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

    private void generateOperationSerializer(
            GenerationContext context,
            OperationShape operation
    ) {
        generateOperationHttpBindingSerializer(context, operation);
    }

    private boolean isRestBinding(HttpBinding.Location location) {
        return location == HttpBinding.Location.HEADER
                || location == HttpBinding.Location.PREFIX_HEADERS
                || location == HttpBinding.Location.LABEL
                || location == HttpBinding.Location.QUERY;
    }

    private void generateOperationHttpBindingSerializer(
            GenerationContext context,
            OperationShape operation
    ) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Model model = context.getModel();
        GoWriter writer = context.getWriter();

        Shape inputShape = model.expectShape(operation.getInput()
                .orElseThrow(() -> new CodegenException("missing input shape for operation: " + operation.getId())));

        HttpBindingIndex bindingIndex = context.getModel().getKnowledge(HttpBindingIndex.class);

        Map<String, HttpBinding> bindingMap = bindingIndex.getRequestBindings(operation).entrySet().stream()
                .filter(entry -> isRestBinding(entry.getValue().getLocation()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        List<String> bindingMembers = new ArrayList<>(bindingMap.keySet());
        bindingMembers.sort(String::compareTo);

        Symbol restEncoder = getRestEncoderSymbol();

        writer.addUseImports(SymbolUtils.createValueSymbolBuilder(null, GoDependency.FMT).build());
        writer.addUseImports(restEncoder);

        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);

        writer.addUseImports(inputSymbol);

        String functionName = ProtocolGenerator.getOperationSerFunctionName(inputSymbol, getProtocolName());

        writer.openBlock("func $L(v $P, encoder $P) error {", "}", functionName, inputSymbol, restEncoder,
                () -> {
                    writer.openBlock("if v == nil {", "}", () -> {
                        writer.write("return fmt.Errorf(\"unsupported serialization of nil %T\", v)");
                    });

                    writer.write("");

                    for (int i = 0; i < bindingMembers.size(); i++) {
                        HttpBinding binding = bindingMap.get(bindingMembers.get(i));
                        writeHttpBindingMember(writer, model, symbolProvider, binding);
                        writer.write("");
                    }

                    writer.write("return nil");
                });
        writer.write("");
    }

    private Symbol getRestEncoderSymbol() {
        return SymbolUtils.createPointableSymbolBuilder("Encoder", GoDependency.AWS_REST_PROTOCOL)
                .build();
    }

    private String generateHttpBindingSetter(Shape targetShape, Symbol targetSymbol, String operand) {
        operand = isDereferenceRequired(targetShape, targetSymbol)
                && targetShape.getType() != ShapeType.BIG_INTEGER || targetShape.getType() != ShapeType.BIG_DECIMAL
                ? "*" + operand : operand;

        switch (targetShape.getType()) {
            case BOOLEAN:
                return ".Boolean(" + operand + ")";
            case STRING:
                operand = targetShape.hasTrait(EnumTrait.class) ? "string(" + operand + ")" : operand;
                return ".String(" + operand + ")";
            case TIMESTAMP:
                return ".UnixTime(" + operand + ")";
            case BYTE:
                return ".Byte(" + operand + ")";
            case SHORT:
                return ".Short(" + operand + ")";
            case INTEGER:
                return ".Integer(" + operand + ")";
            case LONG:
                return ".Long(" + operand + ")";
            case FLOAT:
                return ".Float(" + operand + ")";
            case DOUBLE:
                return ".Double(" + operand + ")";
            case BIG_INTEGER:
                return ".BigInteger(" + operand + ")";
            case BIG_DECIMAL:
                return ".BigDecimal(" + operand + ")";
            default:
                throw new CodegenException("unexpected shape type " + targetShape.getType());
        }
    }

    private void writeHttpBindingMember(
            GoWriter writer,
            Model model,
            SymbolProvider symbolProvider,
            HttpBinding binding
    ) {
        MemberShape memberShape = binding.getMember();
        Shape targetShape = model.expectShape(memberShape.getTarget());
        Symbol targetSymbol = symbolProvider.toSymbol(targetShape);
        String memberName = symbolProvider.toMemberName(memberShape);

        writeSafeFieldAccessor(model, symbolProvider, memberShape, "v", writer, bodyWriter -> {
            switch (binding.getLocation()) {
                case HEADER:
                    if (targetShape instanceof CollectionShape) {
                        Shape collectionMemberShape = model.expectShape(((CollectionShape) targetShape).getMember()
                                .getTarget());
                        Symbol collectionMemberSymbol = symbolProvider.toSymbol(collectionMemberShape);
                        bodyWriter.openBlock("for i := range v.$L {", "}", memberName, () -> {
                            bodyWriter.writeInline("encoder.AddHeader($S)", memberShape.getMemberName());
                            bodyWriter.write(generateHttpBindingSetter(collectionMemberShape, collectionMemberSymbol,
                                    "v.$L[i]"), memberName);
                        });
                    } else {
                        bodyWriter.writeInline("encoder.SetHeader($S)", memberShape.getMemberName());
                        bodyWriter.write(generateHttpBindingSetter(targetShape, targetSymbol, "v.$L"), memberName);
                    }
                    break;
                case PREFIX_HEADERS:
                    if (!targetShape.isMapShape()) {
                        throw new CodegenException("prefix headers must target map shape");
                    }
                    Shape mapValueShape = model.expectShape(targetShape.asMapShape().get().getValue().getTarget());
                    Symbol mapValueSymbol = symbolProvider.toSymbol(targetShape);
                    bodyWriter.write("hv := encoder.Headers($S)", memberName);
                    bodyWriter.openBlock("for i := range v.$L {", "}", memberName, () -> {
                        if (mapValueShape instanceof CollectionShape) {
                            bodyWriter.openBlock("for j := range v.$L[i] {", "}", memberName, () -> {
                                bodyWriter.writeInline("hv.AddHeader($S)", memberShape.getMemberName());
                                bodyWriter.write(generateHttpBindingSetter(mapValueShape, mapValueSymbol, "v.$L[i][j]"),
                                        memberName);
                            });
                        } else {
                            bodyWriter.writeInline("hv.AddHeader($S)", memberShape.getMemberName());
                            bodyWriter.write(generateHttpBindingSetter(mapValueShape, mapValueSymbol, "v.$L[i]"),
                                    memberName);
                        }
                    });
                    break;
                case LABEL:
                    bodyWriter.writeInline("if err := encoder.SetURI($S)", memberShape.getMemberName());
                    bodyWriter.writeInline(generateHttpBindingSetter(targetShape, targetSymbol, "v.$L"), memberName);
                    bodyWriter.write("; err != nil {\n"
                            + "\treturn err\n"
                            + "}");
                    break;
                case QUERY:
                    if (targetShape instanceof CollectionShape) {
                        Shape collectionMemberShape = model.expectShape(((CollectionShape) targetShape).getMember()
                                .getTarget());
                        Symbol collectionMemberSymbol = symbolProvider.toSymbol(collectionMemberShape);
                        bodyWriter.openBlock("for i := range v.$L {", "}", memberName, () -> {
                            bodyWriter.writeInline("encoder.AddQuery($S)", memberShape.getMemberName());
                            bodyWriter.write(generateHttpBindingSetter(collectionMemberShape, collectionMemberSymbol,
                                    "v.$L[i]"), memberName);
                        });
                    } else {
                        bodyWriter.writeInline("encoder.SetQuery($S)", memberShape.getMemberName());
                        bodyWriter.write(generateHttpBindingSetter(targetShape, targetSymbol, "v.$L"), memberName);
                    }
                    break;
                default:
                    throw new CodegenException("unexpected http binding found");
            }
        });
    }

    private boolean isDereferenceRequired(Shape shape, Symbol symbol) {
        boolean pointable = symbol.getProperty("pointable", Boolean.class)
                .orElse(false);

        ShapeType shapeType = shape.getType();

        return pointable
                || shapeType == ShapeType.MAP
                || shapeType == ShapeType.LIST
                || shapeType == ShapeType.SET
                || shapeType == ShapeType.DOCUMENT;
    }

    private void writeSafeFieldAccessor(
            Model model,
            SymbolProvider symbolProvider,
            MemberShape memberShape,
            String structureVariable,
            GoWriter writer,
            Consumer<GoWriter> consumer
    ) {
        Shape targetShape = model.expectShape(memberShape.getTarget());
        Symbol targetSymbol = symbolProvider.toSymbol(targetShape);

        String memberName = symbolProvider.toMemberName(memberShape);

        boolean enumShape = targetShape.hasTrait(EnumTrait.class);

        if (!isDereferenceRequired(targetShape, targetSymbol) && !enumShape) {
            consumer.accept(writer);
            return;
        }

        String conditionCheck = structureVariable + "." + memberName;
        if (enumShape) {
            conditionCheck = "len(" + conditionCheck + ") > 0";
        } else {
            conditionCheck = conditionCheck + " != nil";
        }

        writer.openBlock("if " + conditionCheck + " {", "}", () -> {
            consumer.accept(writer);
        });
    }

    /**
     * Writes the code needed to serialize the input payload of a request.
     *
     * <p>Implementations of this method are expected to set a value to the
     * {@code body} variable that will be serialized as the request body.
     * This variable will already be defined in scope.
     *
     * @param context        The generation context.
     * @param operation      The operation being generated.
     * @param payloadBinding The payload binding to serialize.
     */
    protected void serializeInputPayload(
            GenerationContext context,
            OperationShape operation,
            HttpBinding payloadBinding
    ) {
        // pass
    }

    /**
     * Writes any additional HTTP headers required by the protocol implementation.
     *
     * @param context   The generation context.
     * @param operation The operation being generated.
     */
    protected void writeDefaultHeaders(GenerationContext context, OperationShape operation) {
        // pass
    }

    /**
     * Writes the code needed to serialize the input document of a request.
     *
     * @param context          The generation context.
     * @param operation        The operation being generated.
     * @param documentBindings The bindings to place in the document.
     */
    protected abstract void serializeInputDocument(
            GenerationContext context,
            OperationShape operation,
            List<HttpBinding> documentBindings
    );

    /**
     * Generates serialization functions for shapes in the passed set. These functions
     * should return a value that can then be serialized by the implementation of
     * {@code serializeInputDocument}.
     *
     * @param context The generation context.
     * @param shapes  The shapes to generate serialization for.
     */
    protected abstract void generateDocumentBodyShapeSerializers(GenerationContext context, Set<Shape> shapes);

    /**
     * Generates deserialization functions for shapes in the passed set. These functions
     * should return a value that can then be deserialized by the implementation of
     * {@code deserializeOutputDocument}.
     *
     * @param context The generation context.
     * @param shapes  The shapes to generate deserialization for.
     */
    protected abstract void generateDocumentBodyShapeDeserializers(GenerationContext context, Set<Shape> shapes);

    /**
     * Writes the code needed to deserialize the output document of a response.
     *
     * @param context          The generation context.
     * @param operationOrError The operation or error with a document being deserialized.
     * @param documentBindings The bindings to read from the document.
     */
    protected abstract void deserializeOutputDocument(
            GenerationContext context,
            Shape operationOrError,
            List<HttpBinding> documentBindings
    );

    /**
     * Writes the code that loads an {@code errorCode} String with the content used
     * to dispatch errors to specific serializers.
     *
     * @param context The generation context.
     */
    protected abstract void writeErrorCodeParser(GenerationContext context);

    /**
     * Provides where within the passed output variable the actual error resides. This is useful
     * for protocols that wrap the specific error in additional elements within the body.
     *
     * @param context        The generation context.
     * @param outputLocation The name of the variable containing the output body.
     * @return A string of the variable containing the error body within the output.
     */
    protected String getErrorBodyLocation(GenerationContext context, String outputLocation) {
        return outputLocation;
    }
}
