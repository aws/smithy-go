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

import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
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
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.StreamingTrait;

/**
 * Generates a shape type declaration based on the parameters provided.
 */
public final class ShapeValueGenerator {
    private static final Logger LOGGER = Logger.getLogger(ShapeValueGenerator.class.getName());

    protected final Model model;
    protected final SymbolProvider symbolProvider;

    /**
     * Initializes a shape value generator.
     *
     * @param model          the Smithy model references.
     * @param symbolProvider the symbol provider.
     */
    public ShapeValueGenerator(Model model, SymbolProvider symbolProvider) {
        this.model = model;
        this.symbolProvider = symbolProvider;
    }

    /**
     * Writes generation of a shape value type declaration for the given the parameters.
     *
     * @param writer writer to write generated code with.
     * @param shape  the shape that will be declared.
     * @param params parameters to fill the generated shape declaration.
     */
    public void writeShapeValueInline(GoWriter writer, Shape shape, Node params) {
        if (params.isNullNode()) {
            if (shape.isStringShape() && shape.hasTrait(EnumTrait.class)) {
                Symbol enumSymbol = symbolProvider.toSymbol(shape);
                writer.writeInline("$T($S)", enumSymbol, "");
            } else {
                writer.writeInline("nil");
            }
            return;
        }

        switch (shape.getType()) {
            case STRUCTURE:
                structDeclShapeValue(writer, shape.asStructureShape().get(), () -> {
                    params.accept(new ShapeValueNodeVisitor(writer, this, shape));
                });
                break;

            case SET:
            case LIST:
                listDeclShapeValue(writer, (CollectionShape) shape, () -> {
                    params.accept(new ShapeValueNodeVisitor(writer, this, shape));
                });
                break;

            case MAP:
                mapDeclShapeValue(writer, shape.asMapShape().get(), () -> {
                    params.accept(new ShapeValueNodeVisitor(writer, this, shape));
                });
                break;

            case UNION:
            case DOCUMENT:
                LOGGER.warning("Skipping " + shape.getType() + " shape type not supported, " + shape.getId());
                writer.writeInline("nil");
                break;

            default:

                scalarWrapShapeValue(writer, shape, () -> {
                    params.accept(new ShapeValueNodeVisitor(writer, this, shape));
                });
        }
    }

    /**
     * Writes the declaration for a Go structure. Delegates to the runner for member fields within the structure.
     *
     * @param writer writer to write generated code with.
     * @param shape  the structure shpae
     * @param inner  lambda to populate struct member fields.
     */
    protected void structDeclShapeValue(GoWriter writer, StructureShape shape, Runnable inner) {
        Symbol symbol = symbolProvider.toSymbol(shape);

        writer.write("&$T{", symbol);
        inner.run();
        writer.writeInline("}");
    }

    /**
     * Writes the declaration for a Go slice. Delegates to the runner for fields within the slice.
     *
     * @param writer writer to write generated code with.
     * @param shape  the collection shape
     * @param inner  lambda to populate collection member fields.
     */
    protected void listDeclShapeValue(GoWriter writer, CollectionShape shape, Runnable inner) {
        Shape memberShape = model.expectShape(shape.getMember().getTarget());
        Symbol memberSymbol = symbolProvider.toSymbol(memberShape);

        writer.write("[]$P{", memberSymbol);
        inner.run();
        writer.writeInline("}");
    }

    /**
     * Writes the declaration for a Go map. Delegates to the runner for key/value fields within the map.
     *
     * @param writer writer to write generated code with.
     * @param shape  the map shape.
     * @param inner  lambda to populate map key/value fields.
     */
    protected void mapDeclShapeValue(GoWriter writer, MapShape shape, Runnable inner) {
        Shape valueShape = model.expectShape(shape.getValue().getTarget());
        Shape keyShape = model.expectShape(shape.getKey().getTarget());

        Symbol valueSymbol = symbolProvider.toSymbol(valueShape);
        Symbol keySymbol = symbolProvider.toSymbol(keyShape);

        writer.write("map[$T]$P{", keySymbol, valueSymbol);
        inner.run();
        writer.writeInline("}");
    }

    /**
     * Writes scalar values with pointer value wrapping as needed based on the shape type.
     *
     * @param writer writer to write generated code with.
     * @param shape  scalar shape.
     * @param inner  lambda to populate the actual shape value that will be wrapped.
     */
    protected void scalarWrapShapeValue(GoWriter writer, Shape shape, Runnable inner) {
        boolean withPtrImport = true;
        String closing = ")";

        switch (shape.getType()) {
            case BOOLEAN:
                writer.writeInline("ptr.Bool(");
                break;

            case BLOB:
                if (shape.hasTrait(StreamingTrait.class)) {
                    writer.addUseImports(SmithyGoDependency.SMITHY_IO);
                    writer.addUseImports(SmithyGoDependency.BYTES);
                    writer.writeInline("smithyio.ReadSeekNopCloser{ReadSeeker: bytes.NewReader([]byte(");
                    closing += ")}";
                } else {
                    writer.writeInline("[]byte(");
                }
                withPtrImport = false;
                break;

            case STRING:
                // Enum are not pointers, but string alias values
                if (shape.hasTrait(StreamingTrait.class)) {
                    writer.addUseImports(SmithyGoDependency.SMITHY_IO);
                    writer.addUseImports(SmithyGoDependency.STRINGS);
                    writer.writeInline("smithyio.ReadSeekNopCloser{ReadSeeker: strings.NewReader(");
                    closing += "}";

                } else if (shape.hasTrait(EnumTrait.class)) {
                    Symbol enumSymbol = symbolProvider.toSymbol(shape);
                    writer.writeInline("$T(", enumSymbol);
                    withPtrImport = false;

                } else {
                    writer.writeInline("ptr.String(");
                }

                break;

            case TIMESTAMP:
                writer.writeInline("ptr.Time(");
                break;

            case BYTE:
                writer.writeInline("ptr.Int8(");
                break;

            case SHORT:
                writer.writeInline("ptr.Int16(");
                break;

            case INTEGER:
                writer.writeInline("ptr.Int32(");
                break;

            case LONG:
                writer.writeInline("ptr.Int64(");
                break;

            case FLOAT:
                writer.writeInline("ptr.Float32(");
                break;

            case DOUBLE:
                writer.writeInline("ptr.Float64(");
                break;

            case BIG_INTEGER:
            case BIG_DECIMAL:
                inner.run();
                return;

            default:
                throw new CodegenException("unexpected shape type " + shape.getType());
        }

        if (withPtrImport) {
            writer.addUseImports(SmithyGoDependency.SMITHY_PTR);
        }

        inner.run();
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
            Shape memberShape = model.expectShape(((CollectionShape) this.currentShape).getMember().getTarget());

            node.getElements().forEach(element -> {
                valueGen.writeShapeValueInline(writer, memberShape, element);
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
                Shape memberShape;
                switch (currentShape.getType()) {
                    case STRUCTURE:
                        MemberShape member;
                        if (currentShape.asStructureShape().get().getMember(keyNode.getValue()).isPresent()) {
                            member = currentShape.asStructureShape().get().getMember(keyNode.getValue()).get();
                        } else {
                            throw new CodegenException(
                                    "unknown member " + currentShape.getId() + "." + keyNode.getValue());
                        }

                        memberShape = model.expectShape(member.getTarget());
                        String memberName = symbolProvider.toMemberName(member);

                        writer.write("$L: ", memberName);
                        valueGen.writeShapeValueInline(writer, memberShape, valueNode);
                        writer.write(",");
                        break;

                    case MAP:
                        memberShape = model.expectShape(this.currentShape.asMapShape().get().getValue().getTarget());

                        writer.write("$S: ", keyNode.getValue());
                        valueGen.writeShapeValueInline(writer, memberShape, valueNode);
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
            if (currentShape.getType() == ShapeType.STRING && currentShape.hasTrait(EnumTrait.class)) {
                Symbol enumSymbol = symbolProvider.toSymbol(currentShape);
                writer.writeInline("$T($S)", enumSymbol, "");
            } else {
                writer.writeInline("nil");
            }

            return null;
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
