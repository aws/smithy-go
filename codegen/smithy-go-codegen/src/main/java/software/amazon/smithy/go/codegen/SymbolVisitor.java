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

package software.amazon.smithy.go.codegen;

import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.ReservedWordSymbolProvider;
import software.amazon.smithy.codegen.core.ReservedWords;
import software.amazon.smithy.codegen.core.ReservedWordsBuilder;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.SymbolReference;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.BigDecimalShape;
import software.amazon.smithy.model.shapes.BigIntegerShape;
import software.amazon.smithy.model.shapes.BlobShape;
import software.amazon.smithy.model.shapes.BooleanShape;
import software.amazon.smithy.model.shapes.ByteShape;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.DocumentShape;
import software.amazon.smithy.model.shapes.DoubleShape;
import software.amazon.smithy.model.shapes.FloatShape;
import software.amazon.smithy.model.shapes.IntegerShape;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.LongShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ResourceShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.SetShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.shapes.ShortShape;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.TimestampShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.MediaTypeTrait;
import software.amazon.smithy.utils.StringUtils;

/**
 * Responsible for type mapping and file/identifier formatting.
 *
 * <p>Reserved words for Go are automatically escaped so that they are
 * suffixed with "_". See "reserved-words.txt" for the list of words.
 */
final class SymbolVisitor implements SymbolProvider, ShapeVisitor<Symbol> {

    private static final Logger LOGGER = Logger.getLogger(SymbolVisitor.class.getName());

    private final Model model;
    private final String rootModuleName;
    private final ReservedWordSymbolProvider.Escaper escaper;

    SymbolVisitor(Model model, String rootModuleName) {
        this.model = model;
        this.rootModuleName = rootModuleName;

        // Load reserved words from a new-line delimited file.
        ReservedWords reservedWords = new ReservedWordsBuilder()
                // Since Go only exports names if the first character is upper case and all
                // the go reserved words are lower case, it's functionally impossible to conflict,
                // so we only need to protect against common names. As of now there's only one.
                .put("String", "String_")
                .build();

        escaper = ReservedWordSymbolProvider.builder()
                // TODO: escape reserved member names
                .nameReservedWords(reservedWords)
                // Only escape words when the symbol has a definition file to
                // prevent escaping intentional references to built-in types.
                .escapePredicate((shape, symbol) -> !StringUtils.isEmpty(symbol.getDefinitionFile()))
                .buildEscaper();
    }

    @Override
    public Symbol toSymbol(Shape shape) {
        Symbol symbol = shape.accept(this);
        LOGGER.fine(() -> String.format("Creating symbol from %s: %s", shape, symbol));
        return escaper.escapeSymbol(shape, symbol);
    }

    @Override
    public String toMemberName(MemberShape shape) {
        return escaper.escapeMemberName(StringUtils.capitalize(shape.getMemberName()));
    }

    @Override
    public Symbol blobShape(BlobShape shape) {
        Symbol blobSymbol = createPointableSymbolBuilder(shape, "[]byte").build();
        if (shape.hasTrait(MediaTypeTrait.class)) {
            return createMediaTypeSymbol(shape, blobSymbol);
        }
        return blobSymbol;
    }

    private Symbol createMediaTypeSymbol(Shape shape, Symbol base) {
        // Right now we just use the shape's name. We could also generate a name, something like
        // MediaTypeApplicationJson based on the media type value. This could lead to name conflicts
        // but potentially reduces duplication.
        String name = StringUtils.capitalize(shape.getId().getName());
        return createPointableSymbolBuilder(shape, name, rootModuleName)
                .putProperty("mediaTypeBaseSymbol", base)
                .definitionFile("./api_types.go")
                .addReference(base)
                .build();
    }

    @Override
    public Symbol booleanShape(BooleanShape shape) {
        return createPointableSymbolBuilder(shape, "bool").build();
    }

    @Override
    public Symbol listShape(ListShape shape) {
        return createCollectionSymbol(shape);
    }

    @Override
    public Symbol setShape(SetShape shape) {
        // Go doesn't have a set type. Rather than hack together a set using a map,
        // we instead just create a list and let the service be responsible for
        // asserting that there are no duplicates.
        return createCollectionSymbol(shape);
    }

    private Symbol createCollectionSymbol(CollectionShape shape) {
        Symbol reference = toSymbol(shape.getMember());
        return createSymbolBuilder(shape, "[]" + reference.getName())
                .addReference(reference)
                .build();
    }

    @Override
    public Symbol mapShape(MapShape shape) {
        Symbol reference = toSymbol(shape.getValue());
        return createSymbolBuilder(shape, "map[string]" + reference.getName())
                .addReference(reference)
                .build();
    }

    @Override
    public Symbol byteShape(ByteShape shape) {
        return createPointableSymbolBuilder(shape, "byte").build();
    }

    @Override
    public Symbol shortShape(ShortShape shape) {
        return createPointableSymbolBuilder(shape, "int16").build();
    }

    @Override
    public Symbol integerShape(IntegerShape shape) {
        return createPointableSymbolBuilder(shape, "int32").build();
    }

    @Override
    public Symbol longShape(LongShape shape) {
        return createPointableSymbolBuilder(shape, "int64").build();
    }

    @Override
    public Symbol floatShape(FloatShape shape) {
        return createPointableSymbolBuilder(shape, "float32").build();
    }

    @Override
    public Symbol documentShape(DocumentShape shape) {
        // TODO: implement document shapes
        return createPointableSymbolBuilder(shape, "nil").build();
    }

    @Override
    public Symbol doubleShape(DoubleShape shape) {
        return createPointableSymbolBuilder(shape, "float64").build();
    }

    @Override
    public Symbol bigIntegerShape(BigIntegerShape shape) {
        return createBigSymbol(shape, "big.Int");
    }

    @Override
    public Symbol bigDecimalShape(BigDecimalShape shape) {
        return createBigSymbol(shape, "big.Float");
    }

    private Symbol createBigSymbol(Shape shape, String symbolName) {
        return createPointableSymbolBuilder(shape, symbolName)
                .addReference(createNamespaceReference(GoDependency.BIG))
                .build();
    }

    @Override
    public Symbol operationShape(OperationShape shape) {
        // TODO: implement operations
        return createPointableSymbolBuilder(shape, "nil").build();
    }

    @Override
    public Symbol resourceShape(ResourceShape shape) {
        // TODO: implement resources
        return createPointableSymbolBuilder(shape, "nil").build();
    }

    @Override
    public Symbol serviceShape(ServiceShape shape) {
        // TODO: implement clients
        return createPointableSymbolBuilder(shape, "nil").build();
    }

    @Override
    public Symbol stringShape(StringShape shape) {
        if (shape.hasTrait(EnumTrait.class)) {
            String name = StringUtils.capitalize(shape.getId().getName());
            return createPointableSymbolBuilder(shape, name, rootModuleName)
                    .definitionFile("./api_enums.go")
                    .build();
        }

        Symbol stringSymbol = createPointableSymbolBuilder(shape, "string").build();
        if (shape.hasTrait(MediaTypeTrait.class)) {
            return createMediaTypeSymbol(shape, stringSymbol);
        }
        return stringSymbol;
    }

    @Override
    public Symbol structureShape(StructureShape shape) {
        String name = StringUtils.capitalize(shape.getId().getName());
        return createPointableSymbolBuilder(shape, name, rootModuleName)
                .definitionFile("./api_types.go")
                .build();
    }

    @Override
    public Symbol unionShape(UnionShape shape) {
        String name = StringUtils.capitalize(shape.getId().getName());
        return createPointableSymbolBuilder(shape, name, rootModuleName)
                .definitionFile("./api_types.go")
                .build();
    }

    @Override
    public Symbol memberShape(MemberShape shape) {
        Shape targetShape = model.getShape(shape.getTarget())
                .orElseThrow(() -> new CodegenException("Shape not found: " + shape.getTarget()));
        return toSymbol(targetShape);
    }

    @Override
    public Symbol timestampShape(TimestampShape shape) {
        return createPointableSymbolBuilder(shape, "time.Time")
                .addReference(createNamespaceReference(GoDependency.TIME))
                .build();
    }

    private Symbol.Builder createSymbolBuilder(Shape shape, String typeName) {
        return Symbol.builder().putProperty("shape", shape)
                .putProperty("pointable", false)
                .name(typeName);
    }

    private Symbol.Builder createPointableSymbolBuilder(Shape shape, String typeName) {
        return Symbol.builder().putProperty("shape", shape)
                .putProperty("pointable", true)
                .name(typeName);
    }

    private Symbol.Builder createPointableSymbolBuilder(Shape shape, String typeName, String namespace) {
        return createPointableSymbolBuilder(shape, typeName).namespace(namespace, ".");
    }

    private SymbolReference createNamespaceReference(GoDependency dependency) {
        // Go generally imports an entire package under a single name, which defaults to the last
        // part of the package name path. So we need to create a symbol for that namespace to reference.
        String namespace = dependency.getDependencies().get(0).getPackageName();
        Symbol namespaceSymbol = Symbol.builder()
                // We're not referencing a particular symbol from the namespace, so we leave the name blank.
                .name("")
                .putProperty("namespaceSymbol", true)
                .namespace(namespace, "/")
                .addDependency(dependency)
                .build();
        return SymbolReference.builder()
                .symbol(namespaceSymbol)
                .alias(CodegenUtils.getDefaultPackageImportName(namespace))
                .build();
    }
}
