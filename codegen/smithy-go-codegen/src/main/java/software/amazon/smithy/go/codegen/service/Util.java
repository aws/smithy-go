/*
 * Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.go.codegen.service;

import static java.util.stream.Collectors.toSet;

import java.util.Set;
import java.util.stream.Stream;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;

public final class Util {
    private Util() {}

    public static Set<StructureShape> getShapesToSerde(Model model, StructureShape shape) {
        return Stream.concat(
                Stream.of(shape),
                shape.members().stream()
                    .map(it -> targetOrSelf(model, model.expectShape(it.getTarget())))
                    .filter(Shape::isStructureShape)
                    .flatMap(it -> getShapesToSerde(model, (StructureShape) it).stream())
        ).collect(toSet());
    }

    private static Shape targetOrSelf(Model model, Shape shape) {
        if (shape instanceof CollectionShape) {
            return targetOrSelf(model, model.expectShape(((CollectionShape) shape).getMember().getTarget()));
        } else if (shape instanceof MapShape) {
            return targetOrSelf(model, model.expectShape(((MapShape) shape).getValue().getTarget()));
        }
        return shape;
    }
}
