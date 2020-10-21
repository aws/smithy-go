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
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.GoWriter;
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
     * Generates the registration code steps to register the step slots with the stack identified by stackVariable.
     *
     * @param writer        the code writer
     * @param stackVariable the Go stack variable to manipulate
     */
    public void generateSlotRegistration(GoWriter writer, String stackVariable) {
        validate();
        writeSlotMutators(writer, stackVariable, MiddlewareStackStep.INITIALIZE, initializeSlotMutators);
        writeSlotMutators(writer, stackVariable, MiddlewareStackStep.SERIALIZE, serializeSlotMutators);
        writeSlotMutators(writer, stackVariable, MiddlewareStackStep.BUILD, buildSlotMutators);
        writeSlotMutators(writer, stackVariable, MiddlewareStackStep.FINALIZE, finalizeSlotMutators);
        writeSlotMutators(writer, stackVariable, MiddlewareStackStep.DESERIALIZE, deserializeSlotMutators);
    }

    /**
     * Validate throws a {@link CodegenException} if an attempt is made to register an invalid slot registration.
     */
    private void validate() {
        validateSlotMutators(initializeSlotMutators);
        validateSlotMutators(serializeSlotMutators);
        validateSlotMutators(buildSlotMutators);
        validateSlotMutators(finalizeSlotMutators);
        validateSlotMutators(deserializeSlotMutators);
    }

    private void validateSlotMutators(List<SlotMutator> mutators) {
        List<String> seen = new ArrayList<>();

        for (SlotMutator mutator : mutators) {
            if (mutator.method == Method.INSERT) {
                if (!seen.contains(mutator.relativeTo)) {
                    throw new CodegenException(String.format("slot mutator references %s before existence",
                            mutator.relativeTo));
                }
            }
            for (String identifier : mutator.getIdentifiers()) {
                if (seen.contains(identifier)) {
                    throw new CodegenException(String.format("attempt to register duplicate slot %s", identifier));
                }
                seen.add(identifier);
            }
            if (mutator.getIdentifiers().size() == 0) {
                throw new CodegenException("one or more slot identifiers must be provided");
            }
        }
    }

    private void writeSlotMutators(
            GoWriter writer,
            String stackVariable,
            MiddlewareStackStep step,
            List<SlotMutator> mutators
    ) {
        if (mutators.size() == 0) {
            return;
        }

        mutators.forEach(mutator -> {
            switch (mutator.getMethod()) {
                case ADD:
                    writer.openBlock("$L.$L.$L($T,", ")", stackVariable, step.toString(), "AddSlot",
                            mutator.position.getSymbol(), () -> {
                                mutator.getIdentifiers().forEach(s -> {
                                    writer.write("$S,", s);
                                });
                            });
                    break;
                case INSERT:
                    writer.openBlock("$L.$L.$L($S, $T,", ")", stackVariable, step.toString(), "AddSlot",
                            mutator.getRelativeTo(), mutator.position.getSymbol(), () -> {
                                mutator.getIdentifiers().forEach(s -> {
                                    writer.write("$S,", s);
                                });
                            });
                    break;
                default:
                    throw new CodegenException("unknown slot method mutator");
            }
        });
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
        ADD;
    }

    public static final class SlotMutator {
        private final Method method;
        private final Position position;
        private final String relativeTo;
        private final List<String> identifiers;

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

        public String getRelativeTo() {
            return relativeTo;
        }

        public List<String> getIdentifiers() {
            return identifiers;
        }

        public static Builder builder() {
            return new Builder();
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
            private String relativeTo;
            private final List<String> identifiers = new ArrayList<>();

            public Builder position(Position position) {
                this.position = position;
                return this;
            }

            public Builder method(Method method) {
                this.method = method;
                return this;
            }

            public Builder relativeTo(String relativeTo) {
                this.relativeTo = relativeTo;
                return this;
            }

            public Builder identifiers(List<String> identifiers) {
                this.identifiers.clear();
                this.identifiers.addAll(identifiers);
                return this;
            }

            public Builder addIdentifier(String identifier) {
                this.identifiers.add(identifier);
                return this;
            }

            public Builder removeIdentifier(String identifier) {
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
        public StackSlotRegistrar.Builder merge(StackSlotRegistrar stackSlotRegistrar) {
            stackSlotRegistrar.getInitializeSlotMutators().forEach(this::addInitializeSlotMutator);
            stackSlotRegistrar.getSerializeSlotMutators().forEach(this::addSerializeSlotMutator);
            stackSlotRegistrar.getBuildSlotMutators().forEach(this::addBuildSlotMutator);
            stackSlotRegistrar.getFinalizeSlotMutators().forEach(this::addFinalizeSlotMutators);
            stackSlotRegistrar.getDeserializeSlotMutators().forEach(this::addDeserializeSlotMutators);
            return this;
        }
    }
}
