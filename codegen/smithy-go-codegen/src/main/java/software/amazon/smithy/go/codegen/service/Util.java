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
import software.amazon.smithy.model.shapes.BlobShape;
import software.amazon.smithy.model.shapes.BooleanShape;
import software.amazon.smithy.model.shapes.ByteShape;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.DoubleShape;
import software.amazon.smithy.model.shapes.FloatShape;
import software.amazon.smithy.model.shapes.IntegerShape;
import software.amazon.smithy.model.shapes.LongShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShortShape;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.TimestampShape;

public final class Util {
    private Util() {}

    public static Set<Shape> getShapesToSerde(Model model, Shape shape) {
        return Stream.concat(
                Stream.of(normalize(shape)),
                shape.members().stream()
                    .map(it -> model.expectShape(it.getTarget()))
                    .flatMap(it -> getShapesToSerde(model, it).stream())
        ).filter(it -> !it.getId().toString().equals("smithy.api#Unit")).collect(toSet());
    }

    public static Shape targetOrSelf(Model model, Shape shape) {
        if (shape instanceof CollectionShape) {
            return model.expectShape(((CollectionShape) shape).getMember().getTarget());
        } else if (shape instanceof MapShape) {
            return model.expectShape(((MapShape) shape).getValue().getTarget());
        }
        return shape;
    }

    public static Shape normalize(Shape shape) {
        return switch (shape.getType()) {
            case BLOB -> BlobShape.builder().id("com.amazonaws.synthetic#Blob").build();
            case BOOLEAN -> BooleanShape.builder().id("com.amazonaws.synthetic#Bool").build();
            case STRING -> StringShape.builder().id("com.amazonaws.synthetic#String").build();
            case TIMESTAMP -> TimestampShape.builder().id("com.amazonaws.synthetic#Time").build();
            case BYTE -> ByteShape.builder().id("com.amazonaws.synthetic#Int8").build();
            case SHORT -> ShortShape.builder().id("com.amazonaws.synthetic#Int16").build();
            case INTEGER -> IntegerShape.builder().id("com.amazonaws.synthetic#Int32").build();
            case LONG -> LongShape.builder().id("com.amazonaws.synthetic#Int64").build();
            case FLOAT -> FloatShape.builder().id("com.amazonaws.synthetic#Float32").build();
            case DOUBLE -> DoubleShape.builder().id("com.amazonaws.synthetic#Float64").build();
            default -> shape;
        };
    }
}
