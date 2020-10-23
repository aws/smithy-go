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

package software.amazon.smithy.go.codegen.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.MiddlewareIdentifier;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.utils.SmithyBuilder;

/**
 * Utility for registering step slots for the middleware stack.
 */
public final class StackSlotRegistrar {
    private final List<SlotMutator> initializeSlotMutators;
    private final List<SlotMutator> serializeSlotMutators;
    private final List<SlotMutator> buildSlotMutators;
    private final List<SlotMutator> finalizeSlotMutators;
    private final List<SlotMutator> deserializeSlotMutators;

    private StackSlotRegistrar(Builder builder) {
        this.initializeSlotMutators = builder.initializeSlotMutators;
        this.serializeSlotMutators = builder.serializeSlotMutators;
        this.buildSlotMutators = builder.buildSlotMutators;
        this.finalizeSlotMutators = builder.finalizeSlotMutators;
        this.deserializeSlotMutators = builder.deserializeSlotMutators;
    }

    public List<SlotMutator> getInitializeSlotMutators() {
        return initializeSlotMutators;
    }

    public List<SlotMutator> getSerializeSlotMutators() {
        return serializeSlotMutators;
    }

    public List<SlotMutator> getBuildSlotMutators() {
        return buildSlotMutators;
    }

    public List<SlotMutator> getFinalizeSlotMutators() {
        return finalizeSlotMutators;
    }

    public List<SlotMutator> getDeserializeSlotMutators() {
        return deserializeSlotMutators;
    }

    /**
     * Validate returns whether the registrar represents a valid slot configuration.
     *
     * @return whether the configuration is valid
     */
    public boolean isValid() {
        return validateSlotMutators(initializeSlotMutators)
                && validateSlotMutators(serializeSlotMutators)
                && validateSlotMutators(buildSlotMutators)
                && validateSlotMutators(finalizeSlotMutators)
                && validateSlotMutators(deserializeSlotMutators);
    }

    private boolean validateSlotMutators(List<SlotMutator> mutators) {
        List<MiddlewareIdentifier> seen = new ArrayList<>();

        for (SlotMutator mutator : mutators) {
            if (mutator.getMethod() == Method.INSERT) {
                if (!seen.contains(mutator.getRelativeTo().get())) {
                    return false;
                }
            }
            for (MiddlewareIdentifier identifier : mutator.getIdentifiers()) {
                if (seen.contains(identifier)) {
                    return false;
                }
                seen.add(identifier);
            }
            if (mutator.getIdentifiers().size() == 0) {
                throw new CodegenException("one or more slot identifiers must be provided");
            }
        }

        return true;
    }

    public static StackSlotRegistrar.Builder builder() {
        return new Builder();
    }

    public SmithyBuilder<StackSlotRegistrar> toBuilder() {
        return builder()
                .initializeSlotMutators(initializeSlotMutators)
                .serializeSlotMutators(serializeSlotMutators)
                .buildSlotMutators(buildSlotMutators)
                .finalizeSlotMutators(finalizeSlotMutators)
                .deserializeSlotMutators(deserializeSlotMutators);
    }

    public enum Position {
        BEFORE("Before"),
        AFTER("After");

        private final Symbol symbol;

        Position(String name) {
            symbol = SymbolUtils.createValueSymbolBuilder(name, SmithyGoDependency.SMITHY_MIDDLEWARE).build();
        }

        public Symbol getSymbol() {
            return symbol;
        }
    }

    public enum Method {
        INSERT,
        ADD
    }

    public static final class SlotMutator {
        private final Method method;
        private final Position position;
        private final MiddlewareIdentifier relativeTo;
        private final List<MiddlewareIdentifier> identifiers;

        private SlotMutator(Builder builder) {
            position = SmithyBuilder.requiredState("position", builder.position);
            method = SmithyBuilder.requiredState("method", builder.method);
            if (method == Method.INSERT) {
                relativeTo = SmithyBuilder.requiredState("relativeTo", builder.relativeTo);
            } else {
                relativeTo = null;
            }
            identifiers = SmithyBuilder.requiredState("identifiers", builder.identifiers);
        }

        public Position getPosition() {
            return position;
        }

        public Method getMethod() {
            return method;
        }

        public Optional<MiddlewareIdentifier> getRelativeTo() {
            return Optional.ofNullable(relativeTo);
        }

        public List<MiddlewareIdentifier> getIdentifiers() {
            return identifiers;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static Builder addBefore() {
            return newMethodPositionBuilder(Method.ADD, Position.BEFORE);
        }

        public static Builder addAfter() {
            return newMethodPositionBuilder(Method.ADD, Position.AFTER);
        }

        public static Builder insertBefore(MiddlewareIdentifier relativeTo) {
            return newMethodPositionBuilder(Method.INSERT, Position.BEFORE)
                    .relativeTo(relativeTo);
        }

        public static Builder insertAfter(MiddlewareIdentifier relativeTo) {
            return newMethodPositionBuilder(Method.INSERT, Position.AFTER)
                    .relativeTo(relativeTo);
        }

        private static Builder newMethodPositionBuilder(Method method, Position position) {
            return builder()
                    .method(method)
                    .position(position);
        }

        public SmithyBuilder<SlotMutator> toBuilder() {
            return builder()
                    .method(method)
                    .position(position)
                    .relativeTo(relativeTo)
                    .identifiers(identifiers);
        }

        public static class Builder implements SmithyBuilder<SlotMutator> {
            private Position position;
            private Method method;
            private MiddlewareIdentifier relativeTo;
            private final List<MiddlewareIdentifier> identifiers = new ArrayList<>();

            public Builder position(Position position) {
                this.position = position;
                return this;
            }

            public Builder method(Method method) {
                this.method = method;
                return this;
            }

            public Builder relativeTo(MiddlewareIdentifier relativeTo) {
                this.relativeTo = relativeTo;
                return this;
            }

            public Builder identifiers(List<MiddlewareIdentifier> identifiers) {
                this.identifiers.clear();
                this.identifiers.addAll(identifiers);
                return this;
            }

            public Builder addIdentifier(MiddlewareIdentifier identifier) {
                this.identifiers.add(identifier);
                return this;
            }

            public Builder removeIdentifier(MiddlewareIdentifier identifier) {
                this.identifiers.remove(identifier);
                return this;
            }

            @Override
            public SlotMutator build() {
                return new SlotMutator(this);
            }
        }
    }

    public static class Builder implements SmithyBuilder<StackSlotRegistrar> {
        private final List<SlotMutator> initializeSlotMutators = new ArrayList<>();
        private final List<SlotMutator> serializeSlotMutators = new ArrayList<>();
        private final List<SlotMutator> buildSlotMutators = new ArrayList<>();
        private final List<SlotMutator> finalizeSlotMutators = new ArrayList<>();
        private final List<SlotMutator> deserializeSlotMutators = new ArrayList<>();

        @Override
        public StackSlotRegistrar build() {
            return new StackSlotRegistrar(this);
        }

        public Builder initializeSlotMutators(List<SlotMutator> mutators) {
            this.initializeSlotMutators.clear();
            this.initializeSlotMutators.addAll(mutators);
            return this;
        }

        public Builder addInitializeSlotMutator(SlotMutator mutator) {
            this.initializeSlotMutators.add(mutator);
            return this;
        }

        public Builder serializeSlotMutators(List<SlotMutator> mutators) {
            this.serializeSlotMutators.clear();
            this.serializeSlotMutators.addAll(mutators);
            return this;
        }

        public Builder addSerializeSlotMutator(SlotMutator mutator) {
            this.serializeSlotMutators.add(mutator);
            return this;
        }

        public Builder buildSlotMutators(List<SlotMutator> mutators) {
            this.buildSlotMutators.clear();
            this.buildSlotMutators.addAll(mutators);
            return this;
        }

        public Builder addBuildSlotMutator(SlotMutator mutator) {
            this.buildSlotMutators.add(mutator);
            return this;
        }

        public Builder finalizeSlotMutators(List<SlotMutator> mutators) {
            this.finalizeSlotMutators.clear();
            this.finalizeSlotMutators.addAll(mutators);
            return this;
        }

        public Builder addFinalizeSlotMutators(SlotMutator mutator) {
            this.finalizeSlotMutators.add(mutator);
            return this;
        }

        public Builder deserializeSlotMutators(List<SlotMutator> mutators) {
            this.deserializeSlotMutators.clear();
            this.deserializeSlotMutators.addAll(mutators);
            return this;
        }

        public Builder addDeserializeSlotMutators(SlotMutator mutator) {
            this.deserializeSlotMutators.add(mutator);
            return this;
        }

        /**
         * Merges the slots defined in stackSlotRegistrar into the current registrar. Returns a new copy of the regis
         *
         * @param stackSlotRegistrar the registrar to merge
         * @return the new merged registrar
         */
        public Builder merge(StackSlotRegistrar stackSlotRegistrar) {
            initializeSlotMutators.addAll(stackSlotRegistrar.getInitializeSlotMutators());
            serializeSlotMutators.addAll(stackSlotRegistrar.getSerializeSlotMutators());
            buildSlotMutators.addAll(stackSlotRegistrar.getBuildSlotMutators());
            finalizeSlotMutators.addAll(stackSlotRegistrar.getFinalizeSlotMutators());
            deserializeSlotMutators.addAll(stackSlotRegistrar.getDeserializeSlotMutators());
            return this;
        }
    }
}
