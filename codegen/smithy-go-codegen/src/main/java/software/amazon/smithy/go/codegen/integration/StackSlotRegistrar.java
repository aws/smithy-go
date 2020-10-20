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
    private static final Symbol AFTER_SYMBOL = SymbolUtils.createValueSymbolBuilder("After",
            SmithyGoDependency.SMITHY_MIDDLEWARE).build();

    private final List<String> initializeSlots;
    private final List<String> serializeSlots;
    private final List<String> buildSlots;
    private final List<String> finalizeSlots;
    private final List<String> deserializeSlots;

    private StackSlotRegistrar(Builder builder) {
        this.initializeSlots = builder.initializeSlots;
        this.serializeSlots = builder.serializeSlots;
        this.buildSlots = builder.buildSlots;
        this.finalizeSlots = builder.finalizeSlots;
        this.deserializeSlots = builder.deserializeSlots;
    }

    public List<String> getInitializeSlots() {
        return initializeSlots;
    }

    public List<String> getSerializeSlots() {
        return serializeSlots;
    }

    public List<String> getBuildSlots() {
        return buildSlots;
    }

    public List<String> getFinalizeSlots() {
        return finalizeSlots;
    }

    public List<String> getDeserializeSlots() {
        return deserializeSlots;
    }

    /**
     * Generates the registration code steps to register the step slots with the stack identified by stackVariable.
     *
     * @param writer the code writer
     * @param stackVariable the Go stack variable to manipulate
     */
    public void generateSlotRegistration(GoWriter writer, String stackVariable) {
        writeSlotsToStep(writer, stackVariable, MiddlewareStackStep.INITIALIZE, initializeSlots);
        writeSlotsToStep(writer, stackVariable, MiddlewareStackStep.SERIALIZE, serializeSlots);
        writeSlotsToStep(writer, stackVariable, MiddlewareStackStep.BUILD, buildSlots);
        writeSlotsToStep(writer, stackVariable, MiddlewareStackStep.FINALIZE, finalizeSlots);
        writeSlotsToStep(writer, stackVariable, MiddlewareStackStep.DESERIALIZE, deserializeSlots);
    }

    private void writeSlotsToStep(GoWriter writer, String stackVariable, MiddlewareStackStep step, List<String> slots) {
        if (slots.size() == 0) {
            return;
        }

        writer.openBlock("$L.$L.AddSlot($T,", ")", stackVariable, step.toString(), AFTER_SYMBOL, () -> {
            slots.forEach(s -> {
                writer.write("$S,", s);
            });
        });
    }


    public static StackSlotRegistrar.Builder builder() {
        return new Builder();
    }

    public SmithyBuilder<StackSlotRegistrar> toBuilder() {
        return builder()
                .initializeSlots(initializeSlots)
                .serializeSlots(serializeSlots)
                .buildSlots(buildSlots)
                .finalizeSlots(finalizeSlots)
                .deserializeSlots(deserializeSlots);
    }

    public static class Builder implements SmithyBuilder<StackSlotRegistrar> {
        private final List<String> initializeSlots = new ArrayList<>();
        private final List<String> serializeSlots = new ArrayList<>();
        private final List<String> buildSlots = new ArrayList<>();
        private final List<String> finalizeSlots = new ArrayList<>();
        private final List<String> deserializeSlots = new ArrayList<>();

        @Override
        public StackSlotRegistrar build() {
            return new StackSlotRegistrar(this);
        }

        public Builder initializeSlots(List<String> initializeSlots) {
            this.initializeSlots.clear();
            this.initializeSlots.addAll(initializeSlots);
            return this;
        }

        public Builder addInitializeSlot(String id) {
            this.initializeSlots.add(id);
            return this;
        }

        public Builder removeInitializeSlot(String id) {
            this.initializeSlots.remove(id);
            return this;
        }

        public boolean hasInitalizeSlot(String id) {
            return this.initializeSlots.contains(id);
        }

        public Builder serializeSlots(List<String> serializeSlots) {
            this.serializeSlots.clear();
            this.serializeSlots.addAll(serializeSlots);
            return this;
        }

        public Builder addSerializeSlot(String id) {
            this.serializeSlots.add(id);
            return this;
        }

        public Builder removeSerializeSlot(String id) {
            this.serializeSlots.remove(id);
            return this;
        }

        public boolean hasSerializeSlot(String id) {
            return this.serializeSlots.contains(id);
        }

        public Builder buildSlots(List<String> buildSlots) {
            this.buildSlots.clear();
            this.buildSlots.addAll(buildSlots);
            return this;
        }

        public Builder addBuildSlot(String id) {
            this.buildSlots.add(id);
            return this;
        }

        public Builder removeBuildSlot(String id) {
            this.buildSlots.remove(id);
            return this;
        }

        public boolean hasBuildSlot(String id) {
            return this.buildSlots.contains(id);
        }

        public Builder finalizeSlots(List<String> finalizeSlots) {
            this.finalizeSlots.clear();
            this.finalizeSlots.addAll(finalizeSlots);
            return this;
        }

        public Builder addFinalizeSlot(String id) {
            this.finalizeSlots.add(id);
            return this;
        }

        public Builder removeFinalizeSlot(String id) {
            this.finalizeSlots.remove(id);
            return this;
        }

        public boolean hasFinalizeSlot(String id) {
            return this.finalizeSlots.contains(id);
        }

        public Builder deserializeSlots(List<String> deserializeSlots) {
            this.deserializeSlots.clear();
            this.serializeSlots.addAll(deserializeSlots);
            return this;
        }

        public Builder addDeserializeSlot(String id) {
            this.deserializeSlots.add(id);
            return this;
        }

        public Builder removeDeserializeSlot(String id) {
            this.deserializeSlots.remove(id);
            return this;
        }

        public boolean hasDeserializeSlot(String id) {
            return this.deserializeSlots.contains(id);
        }

        /**
         * Merges the slots defined in stackSlotRegistrar into the current registrar. Returns a new copy of the regis
         *
         * @param stackSlotRegistrar the registrar to merge
         * @return the new merged registrar
         */
        public StackSlotRegistrar.Builder merge(StackSlotRegistrar stackSlotRegistrar) {
            stackSlotRegistrar.getInitializeSlots().forEach(id -> {
                if (hasInitalizeSlot(id)) {
                    throw new CodegenException("attempt to merge duplicate initialize slot " + id);
                }
                addInitializeSlot(id);
            });
            stackSlotRegistrar.getSerializeSlots().forEach(id -> {
                if (hasSerializeSlot(id)) {
                    throw new CodegenException("attempt to merge duplicate serialize slot " + id);
                }
                addSerializeSlot(id);
            });
            stackSlotRegistrar.getBuildSlots().forEach(id -> {
                if (hasBuildSlot(id)) {
                    throw new CodegenException("attempt to merge duplicate buid slot " + id);
                }
                addBuildSlot(id);
            });
            stackSlotRegistrar.getFinalizeSlots().forEach(id -> {
                if (hasFinalizeSlot(id)) {
                    throw new CodegenException("attempt to merge duplicate finalize slot " + id);
                }
                addFinalizeSlot(id);
            });
            stackSlotRegistrar.getDeserializeSlots().forEach(id -> {
                if (hasDeserializeSlot(id)) {
                    throw new CodegenException("attempt to merge duplicate deserialize slot " + id);
                }
                addDeserializeSlot(id);
            });
            return this;
        }
    }
}
