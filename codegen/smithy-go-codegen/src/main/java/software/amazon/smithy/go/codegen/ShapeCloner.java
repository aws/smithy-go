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

import software.amazon.smithy.model.shapes.BigDecimalShape;
import software.amazon.smithy.model.shapes.BigIntegerShape;
import software.amazon.smithy.model.shapes.BlobShape;
import software.amazon.smithy.model.shapes.BooleanShape;
import software.amazon.smithy.model.shapes.ByteShape;
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

/**
 * Clones a shape using the provided renaming strategy
 */
public final class ShapeCloner implements ShapeVisitor<Shape> {
    private final NamingStrategy namingStrategy;

    /**
     * Creates a shape cloner with the provided naming strategy
     *
     * @param strategy the naming strategy for cloned shapes
     */
    public ShapeCloner(NamingStrategy strategy) {
        this.namingStrategy = strategy;
    }

    private MemberShape renameMemberShape(MemberShape memberShape, ShapeId parentShapeId) {
        return memberShape.toBuilder()
                .id(ShapeId.fromParts(parentShapeId.getNamespace(), parentShapeId.getName(),
                        memberShape.toShapeId().getMember().orElse(null)))
                .build();
    }

    @Override
    public Shape blobShape(BlobShape shape) {
        ShapeId shapeId = shape.getId();
        return shape.toBuilder().id(namingStrategy.apply(shapeId))
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape booleanShape(BooleanShape shape) {
        ShapeId shapeId = shape.getId();
        return shape.toBuilder().id(namingStrategy.apply(shapeId))
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape listShape(ListShape shape) {
        ShapeId shapeId = shape.getId();
        ShapeId cloneId = namingStrategy.apply(shapeId);
        ListShape.Builder builder = shape.toBuilder().id(cloneId);
        builder.addMember(renameMemberShape(shape.getMember(), cloneId));
        return builder
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape setShape(SetShape shape) {
        ShapeId shapeId = shape.getId();
        ShapeId cloneId = namingStrategy.apply(shapeId);
        SetShape.Builder builder = shape.toBuilder().id(cloneId);
        builder.addMember(renameMemberShape(shape.getMember(), cloneId));
        return builder
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape mapShape(MapShape shape) {
        ShapeId shapeId = shape.getId();
        ShapeId cloneId = namingStrategy.apply(shapeId);
        MapShape.Builder builder = shape.toBuilder().id(cloneId);
        builder.key(renameMemberShape(shape.getKey(), cloneId));
        builder.value(renameMemberShape(shape.getValue(), cloneId));
        return builder
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape byteShape(ByteShape shape) {
        ShapeId shapeId = shape.getId();
        return shape.toBuilder().id(namingStrategy.apply(shapeId))
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape shortShape(ShortShape shape) {
        ShapeId shapeId = shape.getId();
        return shape.toBuilder().id(namingStrategy.apply(shapeId))
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape integerShape(IntegerShape shape) {
        ShapeId shapeId = shape.getId();
        return shape.toBuilder().id(namingStrategy.apply(shapeId))
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape longShape(LongShape shape) {
        ShapeId shapeId = shape.getId();
        return shape.toBuilder().id(namingStrategy.apply(shapeId))
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape floatShape(FloatShape shape) {
        ShapeId shapeId = shape.getId();
        return shape.toBuilder().id(namingStrategy.apply(shapeId))
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape documentShape(DocumentShape shape) {
        ShapeId shapeId = shape.getId();
        return shape.toBuilder().id(namingStrategy.apply(shapeId))
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape doubleShape(DoubleShape shape) {
        ShapeId shapeId = shape.getId();
        return shape.toBuilder().id(namingStrategy.apply(shapeId))
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape bigIntegerShape(BigIntegerShape shape) {
        ShapeId shapeId = shape.getId();
        return shape.toBuilder().id(namingStrategy.apply(shapeId))
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape bigDecimalShape(BigDecimalShape shape) {
        ShapeId shapeId = shape.getId();
        return shape.toBuilder().id(namingStrategy.apply(shapeId))
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape operationShape(OperationShape shape) {
        ShapeId shapeId = shape.getId();
        return shape.toBuilder().id(namingStrategy.apply(shapeId))
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape resourceShape(ResourceShape shape) {
        ShapeId shapeId = shape.getId();
        return shape.toBuilder().id(namingStrategy.apply(shapeId))
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape serviceShape(ServiceShape shape) {
        ShapeId shapeId = shape.getId();
        return shape.toBuilder().id(namingStrategy.apply(shapeId))
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape stringShape(StringShape shape) {
        ShapeId shapeId = shape.getId();
        return shape.toBuilder().id(namingStrategy.apply(shapeId))
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape structureShape(StructureShape shape) {
        ShapeId shapeId = shape.getId();
        ShapeId cloneId = namingStrategy.apply(shapeId);
        StructureShape.Builder builder = shape.toBuilder().id(cloneId).clearMembers();
        shape.members().forEach(oldMemberShape -> {
            builder.addMember(renameMemberShape(oldMemberShape, cloneId));
        });
        return builder
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape unionShape(UnionShape shape) {
        ShapeId shapeId = shape.getId();
        ShapeId cloneId = namingStrategy.apply(shapeId);
        UnionShape.Builder builder = shape.toBuilder().id(cloneId).clearMembers();
        shape.members().forEach(oldMemberShape -> {
            builder.addMember(renameMemberShape(oldMemberShape, cloneId));
        });
        return builder
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape memberShape(MemberShape shape) {
        ShapeId shapeId = shape.getId();
        return shape.toBuilder().id(namingStrategy.apply(shapeId))
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    @Override
    public Shape timestampShape(TimestampShape shape) {
        ShapeId shapeId = shape.getId();
        return shape.toBuilder().id(namingStrategy.apply(shapeId))
                .addTrait(createSyntheticCloneTrait(shapeId))
                .build();
    }

    private SyntheticClone createSyntheticCloneTrait(ShapeId shapeId) {
        return SyntheticClone.builder()
                .archetype(shapeId)
                .build();
    }

    /**
     * Interface for defining how the given ShapeId will be named when cloned
     */
    public interface NamingStrategy {
        /**
         * Get the shape id to be applied to a cloned shape
         *
         * @param shapeId id of the shape being cloned
         * @return new shape id for the cloned shape
         */
        ShapeId apply(ShapeId shapeId);
    }
}
