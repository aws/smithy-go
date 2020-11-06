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

package software.amazon.smithy.go.codegen;

import java.util.Map;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.knowledge.GoPointableIndex;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.BooleanNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.NodeVisitor;
import software.amazon.smithy.model.node.NullNode;
import software.amazon.smithy.model.node.NumberNode;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.SimpleShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.StreamingTrait;

/**
 * Generates a shape type declaration based on the parameters provided.
 */
public final class ShapeValueGenerator {
    private static final Logger LOGGER = Logger.getLogger(ShapeValueGenerator.class.getName());

    protected final Model model;
    protected final SymbolProvider symbolProvider;
    protected final GoPointableIndex pointableIndex;

    /**
     * Initializes a shape value generator.
     *
     * @param model          the Smithy model references.
     * @param symbolProvider the symbol provider.
     */
    public ShapeValueGenerator(Model model, SymbolProvider symbolProvider) {
        this.model = model;
        this.symbolProvider = symbolProvider;
        this.pointableIndex = new GoPointableIndex(model);
    }

    /**
     * Writes generation of a shape value type declaration for the given the parameters.
     *
     * @param writer writer to write generated code with.
     * @param shape  the shape that will be declared.
     * @param params parameters to fill the generated shape declaration.
     */
    public void writePointableStructureShapeValueInline(GoWriter writer, StructureShape shape, Node params) {
        if (params.isNullNode()) {
            writer.writeInline("nil");
        }

        // Struct shapes are special since they are the only shape that may not have a member reference
        // pointing to them since they are top level shapes.
        Symbol symbol = symbolProvider.toSymbol(shape);
        writer.write("&$T{", symbol);
        params.accept(new ShapeValueNodeVisitor(writer, this, shape));
        writer.writeInline("}");
    }

    /**
     * Writes generation of a member shape value type declaration for the given the parameters.
     *
     * @param writer writer to write generated code with.
     * @param member the shape that will be declared.
     * @param params parameters to fill the generated shape declaration.
     */
    protected void writeMemberValueInline(GoWriter writer, MemberShape member, Node params) {
        Shape targetShape = model.expectShape(member.getTarget());

        // Null params need to be represented as zero values for member,
        if (params.isNullNode()) {
            if (pointableIndex.isNillable(member)) {
                writer.writeInline("nil");

            } else if (targetShape.getType() == ShapeType.STRING && targetShape.hasTrait(EnumTrait.class)) {
                Symbol enumSymbol = symbolProvider.toSymbol(targetShape);
                writer.writeInline("$T($S)", enumSymbol, "");

            } else {
                Symbol shapeSymbol = symbolProvider.toSymbol(member);
                writer.writeInline("func() (v $P) { return v }()", shapeSymbol);
            }
            return;
        }

        switch (targetShape.getType()) {
            case STRUCTURE:
                // Struct shapes are special since they are the only shape that may not have a member reference
                // pointing to them since they are top level shapes.
                structDeclShapeValue(writer, member, params);
                break;

            case SET:
            case LIST:
                listDeclShapeValue(writer, member, params);
                break;

            case MAP:
                mapDeclShapeValue(writer, member, params);
                break;

            case UNION:
                unionDeclShapeValue(writer, member, params.expectObjectNode());
                break;

            case DOCUMENT:
                LOGGER.warning("Skipping " + member.getType() + " shape type not supported, " + member.getId());
                writer.writeInline("nil");
                break;

            default:
                writeScalarPointerInline(writer, member, params);
        }
    }

    /**
     * Writes the declaration for a Go structure. Delegates to the runner for member fields within the structure.
     *
     * @param writer writer to write generated code with.
     * @param member the structure shape
     * @param params parameters to fill the generated shape declaration.
     */
    protected void structDeclShapeValue(GoWriter writer, MemberShape member, Node params) {
        Symbol symbol = symbolProvider.toSymbol(member);

        String addr = "";
        if (pointableIndex.isPointable(member)) {
            addr = "&";
        }

        writer.write("$L$T{", addr, symbol);
        params.accept(new ShapeValueNodeVisitor(writer, this, model.expectShape(member.getTarget())));
        writer.writeInline("}");
    }

    /**
     * Writes the declaration for a Go union.
     *
     * @param writer writer to write generated code with.
     * @param member the union shape.
     * @param params the params.
     */
    protected void unionDeclShapeValue(GoWriter writer, MemberShape member, ObjectNode params) {
        UnionShape targetShape = (UnionShape) model.expectShape(member.getTarget());

        for (Map.Entry<StringNode, Node> entry : params.getMembers().entrySet()) {
            targetShape.getMember(entry.getKey().toString()).ifPresent((unionMember) -> {
                Shape unionTarget = model.expectShape(unionMember.getTarget());

                // Need to manually create a symbol builder for a union member struct type because the "member"
                // of a union will return the inner value type not the member not the member type it self.
                Symbol memberSymbol = SymbolUtils.createPointableSymbolBuilder(
                        symbolProvider.toMemberName(unionMember),
                        symbolProvider.toSymbol(targetShape).getNamespace()
                ).build();

                // Union member types are always pointers
                writer.writeInline("&$T{Value: ", memberSymbol);
                if (unionTarget instanceof SimpleShape) {
                    writeScalarValueInline(writer, unionMember, entry.getValue());
                } else {
                    writeMemberValueInline(writer, unionMember, entry.getValue());
                }
                writer.writeInline("}");
            });

            // TODO [denseListMap] should this be simplified to just first?
            return;
        }
    }

    /**
     * Writes the declaration for a Go slice. Delegates to the runner for fields within the slice.
     *
     * @param writer writer to write generated code with.
     * @param member the collection shape
     * @param params parameters to fill the generated shape declaration.
     */
    protected void listDeclShapeValue(GoWriter writer, MemberShape member, Node params) {
        writer.write("$P{", symbolProvider.toSymbol(member));
        params.accept(new ShapeValueNodeVisitor(writer, this, model.expectShape(member.getTarget())));
        writer.writeInline("}");
    }

    /**
     * Writes the declaration for a Go map. Delegates to the runner for key/value fields within the map.
     *
     * @param writer writer to write generated code with.
     * @param member the map shape.
     * @param params parameters to fill the generated shape declaration.
     */
    protected void mapDeclShapeValue(GoWriter writer, MemberShape member, Node params) {
        writer.write("$P{", symbolProvider.toSymbol(member));
        params.accept(new ShapeValueNodeVisitor(writer, this, model.expectShape(member.getTarget())));
        writer.writeInline("}");
    }

    private void writeScalarWrapper(
            GoWriter writer,
            MemberShape member,
            Node params,
            String funcName,
            TriConsumer<GoWriter, MemberShape, Node> inner
    ) {
        if (pointableIndex.isPointable(member)) {
            writer.addUseImports(SmithyGoDependency.SMITHY_PTR);
            writer.writeInline("ptr." + funcName + "(");
            inner.accept(writer, member, params);
            writer.writeInline(")");
        } else {
            inner.accept(writer, member, params);
        }
    }

    /**
     * Writes scalar values with pointer value wrapping as needed based on the shape type.
     *
     * @param writer writer to write generated code with.
     * @param member scalar shape.
     * @param params parameters to fill the generated shape declaration.
     */
    protected void writeScalarPointerInline(GoWriter writer, MemberShape member, Node params) {
        Shape target = model.expectShape(member.getTarget());

        String funcName = "";
        switch (target.getType()) {
            case BOOLEAN:
                funcName = "Bool";
                break;

            case STRING:
                funcName = "String";
                break;

            case TIMESTAMP:
                funcName = "Time";
                break;

            case BYTE:
                funcName = "Int8";
                break;
            case SHORT:
                funcName = "Int16";
                break;
            case INTEGER:
                funcName = "Int32";
                break;
            case LONG:
                funcName = "Int64";
                break;

            case FLOAT:
                funcName = "Float32";
                break;
            case DOUBLE:
                funcName = "Float64";
                break;

            case BLOB:
                break;

            case BIG_INTEGER:
            case BIG_DECIMAL:
                return;

            default:
                throw new CodegenException("unexpected shape type " + target.getType());
        }

        writeScalarWrapper(writer, member, params, funcName, this::writeScalarValueInline);
    }

    protected void writeScalarValueInline(GoWriter writer, MemberShape member, Node params) {
        Shape target = model.expectShape(member.getTarget());

        String closing = "";
        switch (target.getType()) {
            case BLOB:
                // blob streams are io.Readers not byte slices.
                if (target.hasTrait(StreamingTrait.class)) {
                    writer.addUseImports(SmithyGoDependency.SMITHY_IO);
                    writer.addUseImports(SmithyGoDependency.BYTES);
                    writer.writeInline("smithyio.ReadSeekNopCloser{ReadSeeker: bytes.NewReader([]byte(");
                    closing = "))}";
                } else {
                    writer.writeInline("[]byte(");
                    closing = ")";
                }
                break;

            case STRING:
                // String streams are io.Readers not strings.
                if (target.hasTrait(StreamingTrait.class)) {
                    writer.addUseImports(SmithyGoDependency.SMITHY_IO);
                    writer.addUseImports(SmithyGoDependency.STRINGS);
                    writer.writeInline("smithyio.ReadSeekNopCloser{ReadSeeker: strings.NewReader(");
                    closing = ")}";

                } else if (target.hasTrait(EnumTrait.class)) {
                    // Enum are not pointers, but string alias values
                    Symbol enumSymbol = symbolProvider.toSymbol(target);
                    writer.writeInline("$T(", enumSymbol);
                    closing = ")";
                }
                break;

            default:
                break;
        }

        params.accept(new ShapeValueNodeVisitor(writer, this, target));
        writer.writeInline(closing);
    }

    /**
     * NodeVisitor to walk shape value declarations with node values.
     */
    private final class ShapeValueNodeVisitor implements NodeVisitor<Void> {
        GoWriter writer;
        ShapeValueGenerator valueGen;
        Shape currentShape;

        /**
         * Initializes shape value visitor.
         *
         * @param writer   writer to write generated code with.
         * @param valueGen shape value generator.
         * @param shape    the shape that visiting is relative to.
         */
        private ShapeValueNodeVisitor(GoWriter writer, ShapeValueGenerator valueGen, Shape shape) {
            this.writer = writer;
            this.valueGen = valueGen;
            this.currentShape = shape;
        }

        /**
         * When array nodes elements are encountered.
         *
         * @param node the node
         * @return always null
         */
        @Override
        public Void arrayNode(ArrayNode node) {
            MemberShape memberShape = ((CollectionShape) this.currentShape).getMember();

            node.getElements().forEach(element -> {
                valueGen.writeMemberValueInline(writer, memberShape, element);
                writer.write(",");
            });
            return null;
        }

        /**
         * When an object node elements are encountered.
         *
         * @param node the node
         * @return always null
         */
        @Override
        public Void objectNode(ObjectNode node) {
            node.getMembers().forEach((keyNode, valueNode) -> {
                MemberShape member;
                switch (currentShape.getType()) {
                    case STRUCTURE:
                        if (currentShape.asStructureShape().get().getMember(keyNode.getValue()).isPresent()) {
                            member = currentShape.asStructureShape().get().getMember(keyNode.getValue()).get();
                        } else {
                            throw new CodegenException(
                                    "unknown member " + currentShape.getId() + "." + keyNode.getValue());
                        }

                        String memberName = symbolProvider.toMemberName(member);
                        writer.write("$L: ", memberName);
                        valueGen.writeMemberValueInline(writer, member, valueNode);
                        writer.write(",");
                        break;

                    case MAP:
                        member = this.currentShape.asMapShape().get().getValue();

                        writer.write("$S: ", keyNode.getValue());
                        valueGen.writeMemberValueInline(writer, member, valueNode);
                        writer.write(",");
                        break;

                    default:
                        throw new CodegenException("unexpected shape type " + currentShape.getType());
                }
            });

            return null;
        }

        /**
         * When boolean nodes are encountered.
         *
         * @param node the node
         * @return always null
         */
        @Override
        public Void booleanNode(BooleanNode node) {
            if (!currentShape.getType().equals(ShapeType.BOOLEAN)) {
                throw new CodegenException("unexpected shape type " + currentShape + " for boolean value");
            }

            writer.writeInline("$L", node.getValue() ? "true" : "false");
            return null;
        }

        /**
         * When null nodes are encountered.
         *
         * @param node the node
         * @return always null
         */
        @Override
        public Void nullNode(NullNode node) {
            throw new CodegenException("unexpected null node walked, should not be encountered in walker");
        }

        /**
         * When number nodes are encountered.
         *
         * @param node the node
         * @return always null
         */
        @Override
        public Void numberNode(NumberNode node) {
            switch (currentShape.getType()) {
                case TIMESTAMP:
                    writer.addUseImports(SmithyGoDependency.SMITHY_TIME);
                    writer.writeInline("smithytime.ParseEpochSeconds($L)", node.getValue());
                    break;

                case BYTE:
                case SHORT:
                case INTEGER:
                case LONG:
                case FLOAT:
                case DOUBLE:
                    writer.writeInline("$L", node.getValue());
                    break;

                case BIG_INTEGER:
                    writeInlineBigIntegerInit(writer, node.getValue());
                    break;

                case BIG_DECIMAL:
                    writeInlineBigDecimalInit(writer, node.getValue());
                    break;

                default:
                    throw new CodegenException("unexpected shape type " + currentShape + " for string value");
            }

            return null;
        }

        /**
         * When string nodes are encountered.
         *
         * @param node the node
         * @return always null
         */
        @Override
        public Void stringNode(StringNode node) {
            switch (currentShape.getType()) {
                case BLOB:
                case STRING:
                    writer.writeInline("$S", node.getValue());
                    break;

                case BIG_INTEGER:
                    writeInlineBigIntegerInit(writer, node.getValue());
                    break;

                case BIG_DECIMAL:
                    writeInlineBigDecimalInit(writer, node.getValue());
                    break;

                default:
                    throw new CodegenException("unexpected shape type " + currentShape.getType());
            }

            return null;
        }

        private void writeInlineBigDecimalInit(GoWriter writer, Object value) {
            writer.addUseImports(SmithyGoDependency.BIG);
            writer.writeInline("func() *big.Float {\n"
                            + "    i, ok := big.ParseFloat($S, 10, 200, big.ToNearestAway)\n"
                            + "    if !ok { panic(\"invalid generated param value, \" + $S) }\n"
                            + "    return i"
                            + "}()",
                    value, value);
        }

        private void writeInlineBigIntegerInit(GoWriter writer, Object value) {
            writer.addUseImports(SmithyGoDependency.BIG);
            writer.writeInline("func() *big.Int {\n"
                            + "    i, ok := new(big.Int).SetString($S, 10)\n"
                            + "    if !ok { panic(\"invalid generated param value, \" + $S) }\n"
                            + "    return i"
                            + "}()",
                    value, value);
        }
    }

}
