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

import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.AbstractTrait;
import software.amazon.smithy.model.traits.AbstractTraitBuilder;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.utils.SmithyBuilder;
import software.amazon.smithy.utils.ToSmithyBuilder;

/**
 * Defines a shape as being a clone of another modeled shape
 *
 * Must only be used as a runtime trait-only applied to shapes based on model processing
 */
public final class SyntheticClone extends AbstractTrait implements ToSmithyBuilder<SyntheticClone> {
    public static final ShapeId ID = ShapeId.from("smithy.go.traits#SyntheticClone");

    private static final String ARCHETYPE = "archetype";

    private final ShapeId archetype;

    private SyntheticClone(Builder builder) {
        super(ID, builder.getSourceLocation());
        this.archetype = builder.archetype;
    }

    public static final class Provider extends AbstractTrait.Provider {
        public Provider() {
            super(ID);
        }

        @Override
        public Trait createTrait(ShapeId target, Node value) {
            Builder builder = builder();
            ObjectNode objectNode = value.expectObjectNode();
            builder.archetype(ShapeId.from(objectNode.expectStringMember(ARCHETYPE).getValue()));
            return builder.build();
        }
    }

    /**
     * Get the archetype shape that this clone is based on
     *
     * @return the original archetype shape
     */
    public ShapeId getArchetype() {
        return archetype;
    }

    @Override
    protected Node createNode() {
        return Node.objectNode()
                .withMember(ARCHETYPE, Node.from(getArchetype().toString()));
    }

    @Override
    public SmithyBuilder<SyntheticClone> toBuilder() {
        return builder()
                .archetype(getArchetype());
    }

    /**
     * @return Returns a builder used to create {@link SyntheticClone}.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link SyntheticClone}
     */
    public static final class Builder extends AbstractTraitBuilder<SyntheticClone, Builder> {
        private ShapeId archetype;

        private Builder() {
        }

        @Override
        public SyntheticClone build() {
            return new SyntheticClone(this);
        }

        public Builder archetype(ShapeId archetype) {
            this.archetype = archetype;
            return this;
        }
    }
}
