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

package software.amazon.smithy.go.codegen.knowledge;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import software.amazon.smithy.go.codegen.CodegenUtils;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.KnowledgeIndex;
import software.amazon.smithy.model.knowledge.NeighborProviderIndex;
import software.amazon.smithy.model.knowledge.NullableIndex;
import software.amazon.smithy.model.neighbor.NeighborProvider;
import software.amazon.smithy.model.neighbor.Relationship;
import software.amazon.smithy.model.neighbor.RelationshipType;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.ToShapeId;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.utils.SetUtils;

/**
 * An index that checks if a member or shape type should be a pointer type in Go.
 * <p>
 * Extends the rules of smithy's NullableIndex for Go's translation of the smithy shapes to Go types.
 */
public class GoPointableIndex implements KnowledgeIndex {
    private static final Logger LOGGER = Logger.getLogger(GoPointableIndex.class.getName());

    private static final Set<ShapeType> INHERENTLY_VALUE = SetUtils.of(
            ShapeType.BLOB,
            ShapeType.LIST,
            ShapeType.SET,
            ShapeType.MAP,
            ShapeType.UNION,
            ShapeType.DOCUMENT
    );

    private static final Set<ShapeType> INHERENTLY_POINTABLE = SetUtils.of(
            ShapeType.BIG_DECIMAL,
            ShapeType.BIG_INTEGER
    );

    private final Model model;
    private final Set<ShapeId> pointableShapes = new HashSet<>();

    public GoPointableIndex(Model model) {
        this.model = model;
        NullableIndex nullableIndex = new NullableIndex(model);

        for (Shape shape : model.toSet()) {
            checkShape(nullableIndex, shape);
        }
    }

    private void checkShape(NullableIndex nullableIndex, Shape shape) {
        if (isShapePointable(nullableIndex, shape)) {
            pointableShapes.add(shape.getId());
        }

        switch (shape.getType()) {
            case LIST:
            case SET:
                CollectionShape collection = CodegenUtils.expectCollectionShape(shape);
                checkShape(nullableIndex, collection.getMember());
                break;

            case MAP:
                MapShape mapShape = shape.asMapShape().get();
                checkShape(nullableIndex, mapShape.getValue());
                break;

            default:
                break;
        }
    }

    public static GoPointableIndex of(Model model) {
        return new GoPointableIndex(model);
    }

    private boolean isShapePointable(NullableIndex nullableIndex, Shape shape) {
        // All operation input and output shapes are pointable.
        if (isOperationStruct(shape)) {
            return true;
        }

        // Streamed blob shapes are never pointers because they are interfaces
        if (isBlobStream(shape)) {
            return false;
        }

        if (shape.isServiceShape()) {
            return true;
        }

        // This is odd because its not a go type but a function with receiver
        if (shape.isOperationShape()) {
            return false;
        }

        if (INHERENTLY_POINTABLE.contains(shape.getType())) {
            return true;
        }

        if (INHERENTLY_VALUE.contains(shape.getType()) || isShapeEnum(shape)) {
            return false;
        }

        return nullableIndex.isNullable(shape);
    }

    private boolean isShapeEnum(Shape shape) {
        return shape.getType() == ShapeType.STRING && shape.hasTrait(EnumTrait.class);
    }

    private boolean isBlobStream(Shape shape) {
        return shape.getType() == ShapeType.BLOB && shape.hasTrait(StreamingTrait.ID);
    }

    private boolean isOperationStruct(Shape shape) {
        if (!shape.isStructureShape()) {
            return false;
        }

        if (!shape.getId().getNamespace().equals(CodegenUtils.getSyntheticTypeNamespace())) {
            return false;
        }

        NeighborProvider provider = NeighborProviderIndex.of(model).getReverseProvider();
        for (Relationship relationship : provider.getNeighbors(shape)) {
            RelationshipType relationshipType = relationship.getRelationshipType();
            if (relationshipType == RelationshipType.INPUT || relationshipType == RelationshipType.OUTPUT) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns if the shape should be generated as a Go pointer type or not.
     *
     * @param shape the shape to check if should be pointable type.
     * @return if the shape is should be a Go pointer type.
     */
    public final boolean isPointable(ToShapeId shape) {
        return pointableShapes.contains(shape.toShapeId());
    }
}
