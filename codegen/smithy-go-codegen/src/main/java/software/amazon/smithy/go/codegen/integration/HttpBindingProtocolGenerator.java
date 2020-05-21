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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.go.codegen.CodegenUtils;
import software.amazon.smithy.go.codegen.GoDependency;
import software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.knowledge.HttpBindingIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.neighbor.Walker;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.HttpTrait;
import software.amazon.smithy.model.traits.TimestampFormatTrait;
import software.amazon.smithy.model.traits.TimestampFormatTrait.Format;
import software.amazon.smithy.utils.OptionalUtils;


/**
 * Abstract implementation useful for all protocols that use HTTP bindings.
 */
public abstract class HttpBindingProtocolGenerator implements ProtocolGenerator {

    private static final Logger LOGGER = Logger.getLogger(HttpBindingProtocolGenerator.class.getName());

    private final boolean isErrorCodeInBody;
    private final Set<Shape> serializeDocumentBindingShapes = new TreeSet<>();
    private final Set<ShapeId> serializeErrorBindingShapes = new TreeSet<>();

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
    public void generateSharedSerializerComponents(GenerationContext context) {
        serializeDocumentBindingShapes.addAll(resolveRequiredDocumentShapeSerializers(context.getModel(),
                serializeDocumentBindingShapes));
        generateDocumentBodyShapeSerializers(context, serializeDocumentBindingShapes);
    }

    /**
     * Resolves the entire set of shapes that will require serializers given an initial set of shapes.
     *
     * @param model the model
     * @param shapes the shapes to walk and resolve additional required serializers for
     * @return the complete set of shapes requiring serializers
     */
    private Set<Shape> resolveRequiredDocumentShapeSerializers(Model model, Set<Shape> shapes) {
        Set<ShapeId> processed = new TreeSet<>();
        Set<Shape> resolvedShapes = new TreeSet<>();
        Walker walker = new Walker(model);

        shapes.forEach(shape -> {
            processed.add(shape.getId());
            resolvedShapes.add(shape);
            walker.iterateShapes(shape,
                    relationship -> {
                        switch (relationship.getRelationshipType()) {
                            case STRUCTURE_MEMBER:
                            case UNION_MEMBER:
                            case LIST_MEMBER:
                            case SET_MEMBER:
                            case MAP_VALUE:
                            case MEMBER_TARGET:
                                return true;
                            default:
                                return false;
                        }
                    })
                    .forEachRemaining(walkedShape -> {
                        // MemberShape type itself is not what we are interested in
                        if (walkedShape.getType() == ShapeType.MEMBER) {
                            return;
                        }
                        if (processed.contains(walkedShape.getId())) {
                            return;
                        }
                        if (isShapeTypeDocumentSerializerRequired(walkedShape.getType())) {
                            resolvedShapes.add(walkedShape);
                            processed.add(walkedShape.getId());
                        }
                    });
        });

        return resolvedShapes;
    }

    /**
     * Returns whether a document serializer function is required to serializer the given shape type.
     *
     * @param shapeType the shape type
     * @return whether the shape type requires a document serializer function
     */
    protected boolean isShapeTypeDocumentSerializerRequired(ShapeType shapeType) {
        switch (shapeType) {
            case MAP:
            case LIST:
            case SET:
            case DOCUMENT:
            case STRUCTURE:
            case UNION:
                return true;
            default:
                return false;
        }
    }

    /**
     * Get the operations with HTTP Bindings.
     *
     * @param context the generation context
     * @return the list of operation shapes
     */
    public List<OperationShape> getHttpBindingOperations(GenerationContext context) {
        TopDownIndex topDownIndex = context.getModel().getKnowledge(TopDownIndex.class);

        List<OperationShape> containedOperations = new ArrayList<>();
        for (OperationShape operation : topDownIndex.getContainedOperations(context.getService())) {
            OptionalUtils.ifPresentOrElse(
                    operation.getTrait(HttpTrait.class),
                    httpTrait -> containedOperations.add(operation),
                    () -> LOGGER.warning(String.format(
                            "Unable to fetch %s protocol request bindings for %s because it does not have an "
                                    + "http binding trait", getProtocol(), operation.getId()))
            );
        }
        return containedOperations;
    }

    @Override
    public void generateRequestSerializers(GenerationContext context) {
        for (OperationShape operation : getHttpBindingOperations(context)) {
            generateOperationSerializer(context, operation);
        }
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
        generateOperationSerializerMiddleware(context, operation);
        generateOperationHttpBindingSerializer(context, operation);
        generateOperationDocumentSerializer(context, operation);
        addOperationDocumentShapeBinders(context, operation);
    }

    /**
     * Generates the operation document serializer function.
     *
     * @param context   the generation context
     * @param operation the operation shape being generated
     */
    protected abstract void generateOperationDocumentSerializer(GenerationContext context, OperationShape operation);

    /**
     * Adds the top-level shapes from the operation that bind to the body document that require serializer functions.
     *
     * @param context   the generator context
     * @param operation the operation to add document binders from
     */
    private void addOperationDocumentShapeBinders(GenerationContext context, OperationShape operation) {
        Model model = context.getModel();

        // Walk and add members shapes to the list that will require serializer functions
        Collection<HttpBinding> bindings = model.getKnowledge(HttpBindingIndex.class)
                .getRequestBindings(operation).values();

        for (HttpBinding binding : bindings) {
            Shape targetShape = model.expectShape(binding.getMember().getTarget());
            // Check if the input shape has a members that target the document or payload and require serializers
            if (isShapeTypeDocumentSerializerRequired(targetShape.getType())
                    && (binding.getLocation() == HttpBinding.Location.DOCUMENT
                    || binding.getLocation() == HttpBinding.Location.PAYLOAD)) {
                serializeDocumentBindingShapes.add(targetShape);
            }
        }
    }

    private void generateOperationSerializerMiddleware(GenerationContext context, OperationShape operation) {
        GoStackStepMiddlewareGenerator middleware = GoStackStepMiddlewareGenerator.createSerializeStepMiddleware(
                ProtocolGenerator.getSerializeMiddlewareName(operation.getId(), getProtocolName()
                ));

        SymbolProvider symbolProvider = context.getSymbolProvider();
        Model model = context.getModel();

        Shape inputShape = model.expectShape(operation.getInput()
                .orElseThrow(() -> new CodegenException("expect input shape for operation: " + operation.getId())));

        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);

        ApplicationProtocol applicationProtocol = getApplicationProtocol();
        Symbol requestType = applicationProtocol.getRequestType();

        // TODO: Smithy http binding code generator should not know AWS types, composition would help solve this
        middleware.writeMiddleware(context.getWriter(), (generator, writer) -> {
            writer.addUseImports(GoDependency.FMT);

            // cast input request to smithy transport type, check for failures
            writer.write("request, ok := in.Request.($P)", requestType);
            writer.openBlock("if !ok {", "}", () -> {
                writer.write("return out, metadata, "
                        + "&aws.SerializationError{Err: fmt.Errorf(\"unknown transport type %T\", in.Request)}");
            });
            writer.write("");

            // cast input parameters type to the input type of the operation
            writer.write("input, ok := in.Parameters.($P)", inputSymbol);
            writer.openBlock("if !ok {", "}", () -> {
                writer.write("return out, metadata, "
                        + "&aws.SerializationError{Err: fmt.Errorf(\"unknown input parameters type %T\","
                        + " in.Parameters)}");
            });

            writer.write("");

            boolean withRestBindings = isOperationWithRestBindings(model, operation);

            // we only generate an operations http bindings function if there are bindings
            if (withRestBindings) {
                String serFunctionName = ProtocolGenerator.getOperationHttpBindingsSerFunctionName(inputShape,
                        getProtocolName());
                writer.addUseImports(GoDependency.AWS_REST_PROTOCOL);

                writer.write("restEncoder := rest.NewEncoder(request.Request)");
                writer.openBlock("if err := $L(input, restEncoder); err != nil {", "}", serFunctionName, () -> {
                    writer.write("return out, metadata, &aws.SerializationError{Err: err}");
                });
                writer.write("");
            }

            // delegate the setup and usage of the document serializer function for the protocol
            writeMiddlewareDocumentSerializerDelegator(model, symbolProvider, operation, generator, writer);
            writer.write("");

            if (withRestBindings) {
                writer.openBlock("if err := restEncoder.Encode(); err != nil {", "}", () -> {
                    writer.write("return out, metadata, &aws.SerializationError{Err: err}");
                });
            }

            writer.write("");
            writer.write("return next.$L(ctx, in)", generator.getHandleMethodName());
        });
    }


    /**
     * Generate the document serializer logic for the serializer middleware body.
     *
     * @param model          the model
     * @param symbolProvider the symbol provider
     * @param operation      the operation
     * @param generator      middleware generator definition
     * @param writer         the writer within the middlware context
     */
    protected abstract void writeMiddlewareDocumentSerializerDelegator(
            Model model,
            SymbolProvider symbolProvider,
            OperationShape operation,
            GoStackStepMiddlewareGenerator generator,
            GoWriter writer
    );

    private boolean isRestBinding(HttpBinding.Location location) {
        return location == HttpBinding.Location.HEADER
                || location == HttpBinding.Location.PREFIX_HEADERS
                || location == HttpBinding.Location.LABEL
                || location == HttpBinding.Location.QUERY;
    }

    private boolean isOperationWithRestBindings(Model model, OperationShape operationShape) {
        Collection<HttpBinding> bindings = model.getKnowledge(HttpBindingIndex.class)
                .getRequestBindings(operationShape).values();

        for (HttpBinding binding : bindings) {
            if (isRestBinding(binding.getLocation())) {
                return true;
            }
        }

        return false;
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

        HttpBindingIndex bindingIndex = model.getKnowledge(HttpBindingIndex.class);
        Map<String, HttpBinding> bindingMap = bindingIndex.getRequestBindings(operation).entrySet().stream()
                .filter(entry -> isRestBinding(entry.getValue().getLocation()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> {
                    throw new CodegenException("found duplicate binding entries for same response operation shape");
                }, TreeMap::new));

        Symbol restEncoder = getRestEncoderSymbol();
        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);
        String functionName = ProtocolGenerator.getOperationHttpBindingsSerFunctionName(inputShape, getProtocolName());

        writer.addUseImports(GoDependency.FMT);
        writer.openBlock("func $L(v $P, encoder $P) error {", "}", functionName, inputSymbol, restEncoder,
                () -> {
                    writer.openBlock("if v == nil {", "}", () -> {
                        writer.write("return fmt.Errorf(\"unsupported serialization of nil %T\", v)");
                    });

                    writer.write("");

                    for (Map.Entry<String, HttpBinding> entry : bindingMap.entrySet()) {
                        HttpBinding binding = entry.getValue();
                        writeHttpBindingMember(writer, model, symbolProvider, binding);
                        writer.write("");
                    }
                    writer.write("return nil");
                });
        writer.write("");
    }

    private Symbol getRestEncoderSymbol() {
        return SymbolUtils.createPointableSymbolBuilder("Encoder", GoDependency.AWS_REST_PROTOCOL).build();
    }

    private String generateHttpBindingSetter(
            Model model,
            MemberShape memberShape,
            String operand
    ) {
        Shape targetShape = model.expectShape(memberShape.getTarget());

        // We only need to dereference if we pass the shape around as reference in Go.
        // Note we make two exceptions here: big.Int and big.Float should still be passed as reference to the helper
        // method as they can be arbitrarily large.
        operand = CodegenUtils.isShapePassByReference(targetShape)
                && targetShape.getType() != ShapeType.BIG_INTEGER
                && targetShape.getType() != ShapeType.BIG_DECIMAL
                ? "*" + operand : operand;

        switch (targetShape.getType()) {
            case BOOLEAN:
                return ".Boolean(" + operand + ")";
            case STRING:
                operand = targetShape.hasTrait(EnumTrait.class) ? "string(" + operand + ")" : operand;
                return ".String(" + operand + ")";
            case TIMESTAMP:
                // TODO: This needs to handle formats based on location
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
        String memberName = symbolProvider.toMemberName(memberShape);

        writeSafeOperandAccessor(model, symbolProvider, memberShape, "v", writer, (bodyWriter, operand) -> {
            switch (binding.getLocation()) {
                case HEADER:
                    if (targetShape instanceof CollectionShape) {
                        MemberShape collectionMemberShape = ((CollectionShape) targetShape).getMember();
                        bodyWriter.openBlock("for i := range $L {", "}", operand, () -> {
                            bodyWriter.writeInline("encoder.AddHeader($S)", memberShape.getMemberName());
                            bodyWriter.write(generateHttpBindingSetter(model, collectionMemberShape, "v.$L[i]"),
                                    memberName);
                        });
                    } else {
                        bodyWriter.writeInline("encoder.SetHeader($S)", memberShape.getMemberName());
                        bodyWriter.write(generateHttpBindingSetter(model, memberShape, "$L"), operand);
                    }
                    break;
                case PREFIX_HEADERS:
                    MemberShape valueMemberShape = targetShape.asMapShape()
                            .orElseThrow(() -> new CodegenException("prefix headers must target map shape"))
                            .getValue();
                    Shape valueMemberTarget = model.expectShape(valueMemberShape.getTarget());

                    bodyWriter.write("hv := encoder.Headers($S)", memberName);
                    bodyWriter.openBlock("for i := range $L {", "}", operand, () -> {
                        if (valueMemberTarget instanceof CollectionShape) {
                            MemberShape collectionMemberShape = ((CollectionShape) valueMemberTarget).getMember();
                            bodyWriter.openBlock("for j := range $L[i] {", "}", operand, () -> {
                                bodyWriter.writeInline("hv.AddHeader($S)", memberShape.getMemberName());
                                bodyWriter.write(generateHttpBindingSetter(model, collectionMemberShape, "$L[i][j]"),
                                        operand);
                            });
                        } else {
                            bodyWriter.writeInline("hv.AddHeader($S)", memberShape.getMemberName());
                            bodyWriter.write(generateHttpBindingSetter(model, valueMemberShape, "v.$L[i]"),
                                    memberName);
                        }
                    });
                    break;
                case LABEL:
                    bodyWriter.writeInline("if err := encoder.SetURI($S)", memberShape.getMemberName());
                    bodyWriter.writeInline(generateHttpBindingSetter(model, memberShape, "$L"), operand);
                    bodyWriter.write("; err != nil {\n"
                            + "\treturn err\n"
                            + "}");
                    break;
                case QUERY:
                    if (targetShape instanceof CollectionShape) {
                        MemberShape collectionMember = ((CollectionShape) targetShape).getMember();
                        bodyWriter.openBlock("for i := range $L {", "}", operand, () -> {
                            bodyWriter.writeInline("encoder.AddQuery($S)", memberShape.getMemberName());
                            bodyWriter.write(generateHttpBindingSetter(model, collectionMember, "$L[i]"), operand);
                        });
                    } else {
                        bodyWriter.writeInline("encoder.SetQuery($S)", memberShape.getMemberName());
                        bodyWriter.write(generateHttpBindingSetter(model, memberShape, "v.$L"), memberName);
                    }
                    break;
                default:
                    throw new CodegenException("unexpected http binding found");
            }
        });
    }

    protected boolean isDereferenceRequired(Shape shape, Symbol symbol) {
        boolean pointable = symbol.getProperty(SymbolUtils.POINTABLE, Boolean.class)
                .orElse(false);

        ShapeType shapeType = shape.getType();

        return pointable
                || shapeType == ShapeType.MAP
                || shapeType == ShapeType.LIST
                || shapeType == ShapeType.SET
                || shapeType == ShapeType.DOCUMENT;
    }

    /**
     * Writes a conditional check of the provided operand represented by the member shape.
     * This check is to verify if the provided Go value was set by the user and whether the value
     * should be serialized to the transport request.
     *
     * @param model          the model being generated
     * @param symbolProvider the symbol provider
     * @param memberShape    the member shape being accessed
     * @param operand        the Go operand representing the member shape
     * @param writer         the writer
     * @param consumer       a consumer that will be given the writer to populate the accessor body
     */
    protected void writeSafeOperandAccessor(
            Model model,
            SymbolProvider symbolProvider,
            MemberShape memberShape,
            String operand,
            GoWriter writer,
            BiConsumer<GoWriter, String> consumer
    ) {
        Shape targetShape = model.expectShape(memberShape.getTarget());

        String memberName = symbolProvider.toMemberName(memberShape);

        boolean enumShape = targetShape.hasTrait(EnumTrait.class);

        operand = operand + "." + memberName;

        if (!enumShape && !CodegenUtils.isNilAssignableToShape(model, memberShape)) {
            consumer.accept(writer, operand);
            return;
        }

        String conditionCheck;
        if (enumShape) {
            conditionCheck = "len(" + operand + ") > 0";
        } else {
            conditionCheck = operand + " != nil";
        }

        String resolvedOperand = operand;
        writer.openBlock("if " + conditionCheck + " {", "}", () -> {
            consumer.accept(writer, resolvedOperand);
        });
    }


    @Override
    public void generateResponseDeserializers(GenerationContext context) {
        for (OperationShape operation : getHttpBindingOperations(context)) {
            generateOperationHttpBindingDeserializer(context, operation);
            addErrorShapeBinders(context, operation);
        }

        for (ShapeId errorBinding : serializeErrorBindingShapes) {
            generateErrorHttpBindingDeserializer(context, errorBinding);
        }
    }


    private void generateOperationHttpBindingDeserializer(
            GenerationContext context,
            OperationShape operation
    ) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Model model = context.getModel();
        GoWriter writer = context.getWriter();

        Shape outputShape = model.expectShape(operation.getOutput()
                .orElseThrow(() -> new CodegenException(
                        "missing output shape for operation: " + operation.getId())));

        HttpBindingIndex bindingIndex = model.getKnowledge(HttpBindingIndex.class);
        Map<String, HttpBinding> bindingMap = bindingIndex.getResponseBindings(operation).entrySet().stream()
                .filter(entry -> isRestBinding(entry.getValue().getLocation()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> {
                    throw new CodegenException("found duplicate binding entries for same response operation shape");
                }, TreeMap::new));

        // do not generate if no HTTPBinding for operation output
        if (bindingMap.size() == 0) {
            return;
        }

        generateShapeDeserializerFunction(writer, model, symbolProvider, outputShape, bindingMap);
    }

    private void generateErrorHttpBindingDeserializer(
            GenerationContext context,
            ShapeId errorBinding
    ) {
        SymbolProvider symbolProvider = context.getSymbolProvider();
        Model model = context.getModel();
        GoWriter writer = context.getWriter();
        HttpBindingIndex bindingIndex = model.getKnowledge(HttpBindingIndex.class);
        Shape errorBindingShape = model.expectShape(errorBinding);

        Map<String, HttpBinding> bindingMap = bindingIndex.getResponseBindings(errorBinding).entrySet().stream()
                .filter(entry -> isRestBinding(entry.getValue().getLocation()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> {
                    throw new CodegenException("found duplicate binding entries for same error shape");
                }, TreeMap::new));

        // do not generate if no HTTPBinding for Error Binding
        if (bindingMap.size() == 0) {
            return;
        }

        generateShapeDeserializerFunction(writer, model, symbolProvider, errorBindingShape, bindingMap);
    }

    private void generateShapeDeserializerFunction(
            GoWriter writer,
            Model model,
            SymbolProvider symbolProvider,
            Shape targetShape,
            Map<String, HttpBinding> bindingMap
    ) {
        Symbol targetSymbol = symbolProvider.toSymbol(targetShape);
        Symbol smithyHttpResponsePointableSymbol = SymbolUtils.createPointableSymbolBuilder(
                "Response", GoDependency.SMITHY_HTTP_TRANSPORT).build();

        writer.addUseImports(GoDependency.FMT);

        String functionName = ProtocolGenerator.getOperationDeserFunctionName(targetSymbol, getProtocolName());
        writer.openBlock("func $L(v $P, response $P) error {", "}",
                functionName, targetSymbol, smithyHttpResponsePointableSymbol,
                () -> {
                    writer.openBlock("if v == nil {", "}", () -> {
                        writer.write("return fmt.Errorf(\"unsupported deserialization for nil %T\", v)");
                    });
                    writer.write("");

                    for (Map.Entry<String, HttpBinding> entry : bindingMap.entrySet()) {
                        HttpBinding binding = entry.getValue();
                        writeRestDeserializerMember(writer, model, symbolProvider, binding);
                        writer.write("");
                    }
                    writer.write("return nil");
                });
    }


    private void addErrorShapeBinders(GenerationContext context, OperationShape operation) {
        for (ShapeId errorBinding : operation.getErrors()) {
            serializeErrorBindingShapes.add(errorBinding);
        }
    }

    private String generateHttpBindingsValue(
            GoWriter writer,
            Model model,
            Shape targetShape,
            HttpBinding binding,
            String operand
    ) {
        String value = "";
        switch (targetShape.getType()) {
            case STRING:
                if (targetShape.hasTrait(EnumTrait.class)) {
                    value = String.format("types.%s(%s)", targetShape.getId().getName(), operand);
                    return value;
                }
                return operand;
            case BOOLEAN:
                writer.addUseImports(GoDependency.STRCONV);
                return String.format("strconv.ParseBool(%s)", operand);
            case TIMESTAMP:
                writer.addUseImports(GoDependency.AWS_PRIVATE_PROTOCOL);
                HttpBindingIndex bindingIndex = model.getKnowledge(HttpBindingIndex.class);
                TimestampFormatTrait.Format format = bindingIndex.determineTimestampFormat(
                        targetShape,
                        binding.getLocation(),
                        Format.HTTP_DATE
                );
                writer.write(String.format("t, err := protocol.parseTime(protocol.%s, %s)",
                        CodegenUtils.getTimeStampFormatName(format), operand));
                writer.write("if err != nil { return err }");
                return "t";
            case BYTE:
                writer.addUseImports(GoDependency.STRCONV);
                writer.write("i, err := strconv.ParseInt($L,0,8)", operand);
                writer.write("if err != nil { return err }");
                return String.format("byte(i)");
            case SHORT:
                writer.addUseImports(GoDependency.STRCONV);
                return String.format("strconv.ParseInt(%s,0,16)", operand);
            case INTEGER:
                writer.addUseImports(GoDependency.STRCONV);
                return String.format("strconv.ParseInt(%s,0,32)", operand);
            case LONG:
                writer.addUseImports(GoDependency.STRCONV);
                return String.format("strconv.ParseInt(%s,0,64)", operand);
            case FLOAT:
                writer.addUseImports(GoDependency.STRCONV);
                return String.format("strconv.ParseFloat(%s,0,32)", operand);
            case DOUBLE:
                writer.addUseImports(GoDependency.STRCONV);
                return String.format("strconv.ParseFloat(%s,0,64)", operand);
            case BIG_INTEGER:
                writer.addUseImports(GoDependency.BIG);
                writer.write("i := big.Int{}");
                writer.write("bi, ok := i.SetString($L,0)", operand);
                writer.openBlock("if !ok {", "}", () -> {
                    writer.write(
                            "return fmt.Error($S)",
                            "Incorrect conversion from string to BigInteger type"
                    );
                });
                return "*bi";
            case BIG_DECIMAL:
                writer.addUseImports(GoDependency.BIG);
                writer.write("f := big.NewFloat(0)");
                writer.write("bd, ok := f.SetString($L,0)", operand);
                writer.openBlock("if !ok {", "}", () -> {
                    writer.write(
                            "return fmt.Error($S)",
                            "Incorrect conversion from string to BigDecimal type"
                    );
                });
                return "*bd";
            case BLOB:
                writer.addUseImports(GoDependency.BASE64);
                writer.write("b, err := base64.StdEncoding.DecodeString($L)", operand);
                writer.write("if err != nil { return err }");
                return "b";
            case STRUCTURE:
                // Todo: delegate to the shape deserializer
                break;
            case SET:
                // handle set as target shape
                Shape targetValueSetShape = model.expectShape(targetShape.asSetShape().get().getMember().getTarget());
                return getCollectionDeserializer(writer, model, targetValueSetShape, binding, operand);
            case LIST:
                // handle list as target shape
                Shape targetValueListShape = model.expectShape(targetShape.asListShape().get().getMember().getTarget());
                return getCollectionDeserializer(writer, model, targetValueListShape, binding, operand);
            default:
                throw new CodegenException("unexpected shape type " + targetShape.getType());
        }
        return value;
    }

    private String getCollectionDeserializer(
            GoWriter writer, Model model,
            Shape targetShape, HttpBinding binding, String operand
    ) {
        writer.write("list := make([]$L, 0, 0)", targetShape.getId().getName());

        writer.addUseImports(GoDependency.STRINGS);
        writer.openBlock("for _, i := range strings.Split($L[1:len($L)-1], $S) {",
                "}", operand, operand, ",",
                () -> {
                    writer.write("list.add($L)",
                            generateHttpBindingsValue(writer, model, targetShape, binding,
                                    "i"));
                });
        return "list";
    }

    private void writeRestDeserializerMember(
            GoWriter writer,
            Model model,
            SymbolProvider symbolProvider,
            HttpBinding binding
    ) {
        MemberShape memberShape = binding.getMember();
        Shape targetShape = model.expectShape(memberShape.getTarget());
        String memberName = symbolProvider.toMemberName(memberShape);

        switch (binding.getLocation()) {
            case HEADER:
                writeHeaderDeserializerFunction(writer, model, memberName, targetShape, binding);
                break;
            case PREFIX_HEADERS:
                if (!targetShape.isMapShape()) {
                    throw new CodegenException("unexpected prefix-header shape type found in Http bindings");
                }
                writePrefixHeaderDeserializerFunction(writer, model, memberName, targetShape, binding);
                break;
            case PAYLOAD:
                switch (targetShape.getType()) {
                    case BLOB:
                        writer.openBlock("if val := response.Header.Get($S); val != $S {",
                                "}", binding.getLocationName(), "", () -> {
                                    writer.write("v.$L = $L", memberName, "val");
                                });
                        break;
                    case STRUCTURE:
                        // Todo deligate to unmarshaler for structure
                        break;
                    default:
                        throw new CodegenException("unexpected payload type found in http binding");
                }
                break;
            default:
                throw new CodegenException("unexpected http binding found");
        }
    }

    private void writeHeaderDeserializerFunction(
            GoWriter writer, Model model, String memberName,
            Shape targetShape, HttpBinding binding
    ) {
        writer.openBlock("if val := response.Header.Get($S); val != $S {", "}",
                binding.getLocationName(), "", () -> {
                    String value = generateHttpBindingsValue(writer, model, targetShape, binding, "val");
                    writer.write("v.$L = $L", memberName,
                            CodegenUtils.generatePointerReferenceIfPointable(targetShape, value));
                });
    }

    private void writePrefixHeaderDeserializerFunction(
            GoWriter writer, Model model, String memberName,
            Shape targetShape, HttpBinding binding
    ) {
        String prefix = binding.getLocationName();
        Shape targetValueShape = model.expectShape(targetShape.asMapShape().get().getValue().getTarget());
        for (Shape shape : targetShape.asMapShape().get().members()) {
            String name = shape.getId().getName();
            String locationName = prefix + name;
            writer.openBlock("if val := response.Header.Get($S); val != $S {",
                    "}", locationName, "", () -> {
                        writer.write("v.$L[$L] = $L", memberName, name,
                                generateHttpBindingsValue(writer, model, targetValueShape, binding, "val"));
                    });
        }
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

    /**
     * Generates deserialization functions for shapes in the passed set. These functions
     * should return a value that can then be deserialized by the implementation of
     * {@code deserializeOutputDocument}.
     *
     * @param context The generation context.
     * @param shapes  The shapes to generate deserialization for.
     */
    protected abstract void generateDocumentBodyShapeDeserializers(GenerationContext context, Set<Shape> shapes);
}
