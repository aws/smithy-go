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

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.ReservedWordSymbolProvider;
import software.amazon.smithy.codegen.core.ReservedWords;
import software.amazon.smithy.codegen.core.ReservedWordsBuilder;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
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
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.shapes.ShortShape;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.TimestampShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.ErrorTrait;
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
    private final String typesPackageName;
    private final ReservedWordSymbolProvider.Escaper escaper;
    private final ReservedWordSymbolProvider.Escaper errorMemberEscaper;
    private final Map<ShapeId, ReservedWordSymbolProvider.Escaper> structureSpecificMemberEscapers = new HashMap<>();


    SymbolVisitor(Model model, String rootModuleName) {
        this.model = model;
        this.rootModuleName = rootModuleName;
        this.typesPackageName = rootModuleName + "/types";

        ReservedWords reservedWords = new ReservedWordsBuilder()
                // Since Go only exports names if the first character is upper case and all
                // the go reserved words are lower case, it's functionally impossible to conflict,
                // so we only need to protect against common names. As of now there's only one.
                .put("String", "String_")
                .build();

        escaper = ReservedWordSymbolProvider.builder()
                .nameReservedWords(reserveInterfaces(model))
                .memberReservedWords(reservedWords)
                // Only escape words when the symbol has a definition file to
                // prevent escaping intentional references to built-in types.
                .escapePredicate((shape, symbol) -> !StringUtils.isEmpty(symbol.getDefinitionFile()))
                .buildEscaper();

        // Reserved words that only apply to error members.
        ReservedWords reservedErrorMembers = new ReservedWordsBuilder()
                .put("ErrorCode", "ErrorCode_")
                .put("ErrorFault", "ErrorFault_")
                .put("Unwrap", "Unwrap_")
                .put("Error", "Error_")
                .build();

        errorMemberEscaper = ReservedWordSymbolProvider.builder()
                .memberReservedWords(ReservedWords.compose(reservedWords, reservedErrorMembers))
                .escapePredicate((shape, symbol) -> !StringUtils.isEmpty(symbol.getDefinitionFile()))
                .buildEscaper();
    }

    /**
     * Reserves interface names for any structure that supports inheritance.
     *
     * <p>For each of these structures, Get* and Has* methods are reserved for their members.
     *
     * @param model The model whose inheritable structures should have reserved interfaces.
     * @return ReservedWords containing all the reserved interface names.
     */
    private ReservedWords reserveInterfaces(Model model) {
        ReservedWordsBuilder builder = new ReservedWordsBuilder();
        model.shapes(StructureShape.class)
                .filter(this::supportsInheritance)
                .forEach(shape -> {
                    reserveInterfaceMemberAccessors(shape);
                    String name = getDefaultShapeName(shape) + "Interface";
                    builder.put(name, escapeWithTrailingUnderscore(name));
                });
        return builder.build();
    }

    private boolean supportsInheritance(Shape shape) {
        return shape.isStructureShape() && shape.hasTrait(ErrorTrait.class);
    }

    /**
     * Reserves Get* and Has* member names for the given structure for use as accessor methods.
     *
     * <p>These reservations will only apply to the given structure, not to other structures.
     *
     * @param shape The structure shape whose members should be reserved.
     */
    private void reserveInterfaceMemberAccessors(StructureShape shape) {
        ReservedWordsBuilder builder = new ReservedWordsBuilder();
        for (MemberShape member : shape.getAllMembers().values()) {
            String name = getDefaultMemberName(member);
            String getterName = "Get" + name;
            String haserName = "Has" + name;
            builder.put(getterName, escapeWithTrailingUnderscore(getterName));
            builder.put(haserName, escapeWithTrailingUnderscore(haserName));
        }
        ReservedWordSymbolProvider.Escaper structureSpecificMemberEscaper = ReservedWordSymbolProvider.builder()
                .memberReservedWords(builder.build())
                .buildEscaper();
        structureSpecificMemberEscapers.put(shape.getId(), structureSpecificMemberEscaper);
    }

    private String escapeWithTrailingUnderscore(String symbolName) {
        return symbolName + "_";
    }

    private String getDefaultShapeName(Shape shape) {
        return StringUtils.capitalize(shape.getId().getName());
    }

    @Override
    public Symbol toSymbol(Shape shape) {
        Symbol symbol = shape.accept(this);
        LOGGER.fine(() -> String.format("Creating symbol from %s: %s", shape, symbol));
        return linkArchetypeShape(shape, escaper.escapeSymbol(shape, symbol));
    }

    /**
     * Links the archetype shape id for the symbol
     *
     * @param shape  the model shape
     * @param symbol the symbol to set the archetype property on
     * @return the symbol with archetype set if shape is a synthetic clone otherwise the original symbol
     */
    private Symbol linkArchetypeShape(Shape shape, Symbol symbol) {
        return shape.getTrait(SyntheticClone.class)
                .map(syntheticClone -> symbol.toBuilder()
                        .putProperty("archetype", syntheticClone.getArchetype())
                        .build())
                .orElse(symbol);
    }

    @Override
    public String toMemberName(MemberShape shape) {
        String memberName = escaper.escapeMemberName(getDefaultMemberName(shape));

        // Escape words reserved for the specific container.
        if (structureSpecificMemberEscapers.containsKey(shape.getContainer())) {
            memberName = structureSpecificMemberEscapers.get(shape.getContainer()).escapeMemberName(memberName);
        }

        // Escape words that are only reserved for error members.
        if (isErrorMember(shape)) {
            memberName = errorMemberEscaper.escapeMemberName(memberName);
        }
        return memberName;
    }

    private String getDefaultMemberName(MemberShape shape) {
        return StringUtils.capitalize(shape.getMemberName());
    }

    private boolean isErrorMember(MemberShape shape) {
        return model.getShape(shape.getContainer())
                .map(container -> container.hasTrait(ErrorTrait.ID))
                .orElse(false);
    }

    @Override
    public Symbol blobShape(BlobShape shape) {
        Symbol blobSymbol = SymbolUtils.createPointableSymbolBuilder(shape, "[]byte").build();
        if (shape.hasTrait(MediaTypeTrait.class)) {
            return createMediaTypeSymbol(shape, blobSymbol);
        }
        return blobSymbol;
    }

    private Symbol createMediaTypeSymbol(Shape shape, Symbol base) {
        // Right now we just use the shape's name. We could also generate a name, something like
        // MediaTypeApplicationJson based on the media type value. This could lead to name conflicts
        // but potentially reduces duplication.
        String name = getDefaultShapeName(shape);
        return SymbolUtils.createPointableSymbolBuilder(shape, name, typesPackageName)
                .putProperty("mediaTypeBaseSymbol", base)
                .definitionFile("./types/types.go")
                .addReference(base)
                .build();
    }

    @Override
    public Symbol booleanShape(BooleanShape shape) {
        return SymbolUtils.createPointableSymbolBuilder(shape, "bool").build();
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
        return SymbolUtils.createValueSymbolBuilder(shape, "[]" + reference.getName())
                .addReference(reference)
                .build();
    }

    @Override
    public Symbol mapShape(MapShape shape) {
        Symbol reference = toSymbol(shape.getValue());
        return SymbolUtils.createValueSymbolBuilder(shape, "map[string]" + reference.getName())
                .addReference(reference)
                .build();
    }

    @Override
    public Symbol byteShape(ByteShape shape) {
        return SymbolUtils.createPointableSymbolBuilder(shape, "byte").build();
    }

    @Override
    public Symbol shortShape(ShortShape shape) {
        return SymbolUtils.createPointableSymbolBuilder(shape, "int16").build();
    }

    @Override
    public Symbol integerShape(IntegerShape shape) {
        return SymbolUtils.createPointableSymbolBuilder(shape, "int32").build();
    }

    @Override
    public Symbol longShape(LongShape shape) {
        return SymbolUtils.createPointableSymbolBuilder(shape, "int64").build();
    }

    @Override
    public Symbol floatShape(FloatShape shape) {
        return SymbolUtils.createPointableSymbolBuilder(shape, "float32").build();
    }

    @Override
    public Symbol documentShape(DocumentShape shape) {
        return SymbolUtils.createValueSymbolBuilder(shape, "smithy.Document")
                .addReference(SymbolUtils.createNamespaceReference(GoDependency.SMITHY, "smithy"))
                .build();
    }

    @Override
    public Symbol doubleShape(DoubleShape shape) {
        return SymbolUtils.createPointableSymbolBuilder(shape, "float64").build();
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
        return SymbolUtils.createPointableSymbolBuilder(shape, symbolName)
                .addReference(SymbolUtils.createNamespaceReference(GoDependency.BIG))
                .build();
    }

    @Override
    public Symbol operationShape(OperationShape shape) {
        // TODO: implement operations
        return SymbolUtils.createPointableSymbolBuilder(shape, "nil").build();
    }

    @Override
    public Symbol resourceShape(ResourceShape shape) {
        // TODO: implement resources
        return SymbolUtils.createPointableSymbolBuilder(shape, "nil").build();
    }

    @Override
    public Symbol serviceShape(ServiceShape shape) {
        // TODO: implement client generation
        return SymbolUtils.createValueSymbolBuilder(shape, "Client", rootModuleName)
                .definitionFile("./api_client.go")
                .build();
    }

    @Override
    public Symbol stringShape(StringShape shape) {
        if (shape.hasTrait(EnumTrait.class)) {
            String name = getDefaultShapeName(shape);
            return SymbolUtils.createValueSymbolBuilder(shape, name, typesPackageName)
                    .definitionFile("./types/enums.go")
                    .build();
        }

        Symbol stringSymbol = SymbolUtils.createPointableSymbolBuilder(shape, "string").build();
        if (shape.hasTrait(MediaTypeTrait.class)) {
            return createMediaTypeSymbol(shape, stringSymbol);
        }
        return stringSymbol;
    }

    @Override
    public Symbol structureShape(StructureShape shape) {
        String name = getDefaultShapeName(shape);
        Symbol.Builder builder = SymbolUtils.createPointableSymbolBuilder(shape, name, typesPackageName);
        if (shape.hasTrait(ErrorTrait.ID)) {
            builder.definitionFile("./types/errors.go")
                    .addReference(SymbolUtils.createNamespaceReference(GoDependency.SMITHY, "smithy"))
                    .addReference(SymbolUtils.createNamespaceReference(GoDependency.FMT));
        } else {
            builder.definitionFile("./types/types.go");
        }
        return builder.build();
    }

    @Override
    public Symbol unionShape(UnionShape shape) {
        String name = getDefaultShapeName(shape);
        return SymbolUtils.createPointableSymbolBuilder(shape, name, typesPackageName)
                .definitionFile("./types/types.go")
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
        return SymbolUtils.createPointableSymbolBuilder(shape, "time.Time")
                .addReference(SymbolUtils.createNamespaceReference(GoDependency.TIME))
                .build();
    }
}
