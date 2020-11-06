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
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.KnowledgeIndex;
import software.amazon.smithy.model.knowledge.NullableIndex;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.shapes.ToShapeId;
import software.amazon.smithy.model.traits.EnumTrait;

/**
 * An index that checks if a member or shape type should be nullable in Go.
 * <p>
 * Extends the rules of smithy's NullableIndex with the strings of Enums shapes are also considered non-null.
 */
public class GoNullableIndex implements KnowledgeIndex {
    private final Set<ShapeId> notNullableShapes = new HashSet<>();
    private final NullableIndex nullableIndex;

    public GoNullableIndex(Model model) {
        nullableIndex = new NullableIndex(model);
        for (Shape shape : model.toSet()) {
            if (isShapeEnum(shape)) {
                notNullableShapes.add(shape.getId());
            }
        }
    }

    public static GoNullableIndex of(Model model) {
        return new GoNullableIndex(model);
    }

    private boolean isShapeEnum(Shape shape) {
        return shape.getType() == ShapeType.STRING && shape.hasTrait(EnumTrait.class);
    }

    /**
     * Returns if the shape is nullable or not. Wraps smithy's NullableIndex, and
     * extends it with Enums not being nullable.
     *
     * @param shape the shape to check if nullable
     * @return if the shape is nullable or not
     */
    public final boolean isNullable(ToShapeId shape) {
        if (notNullableShapes.contains(shape.toShapeId())) {
            return false;
        }
        return nullableIndex.isNullable(shape);
    }
}
