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

import java.util.Objects;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.utils.SmithyBuilder;
import software.amazon.smithy.utils.ToSmithyBuilder;


public class MiddlewareRegistrar implements ToSmithyBuilder<MiddlewareRegistrar> {
    private final Symbol resolvedFunction;
    private final Symbol functionArgument;

    public MiddlewareRegistrar(Builder builder) {
        this.resolvedFunction = builder.resolvedFunction;
        this.functionArgument = builder.functionArgument;
    }

    /**
     * @return Returns symbol that resolves to a function.
     */
    public Symbol getResolvedFunction() {
        return resolvedFunction;
    }

    /**
     * @return Returns a symbol denoting the argument of the resolved function.
     */
    public Symbol getFunctionArgument() {
        return functionArgument;
    }

    @Override
    public SmithyBuilder<MiddlewareRegistrar> toBuilder() {
        return builder().functionArgument(functionArgument).resolvedFunction(resolvedFunction);
    }

    public static MiddlewareRegistrar.Builder builder() {
        return new MiddlewareRegistrar.Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MiddlewareRegistrar that = (MiddlewareRegistrar) o;
        return Objects.equals(getResolvedFunction(), that.getResolvedFunction())
                && Objects.equals(getFunctionArgument(), that.getFunctionArgument());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getResolvedFunction(), getFunctionArgument());
    }


    /**
     * Builds a MiddlewareRegistrar.
     */
    public static class Builder implements SmithyBuilder<MiddlewareRegistrar> {
        private Symbol resolvedFunction;
        private Symbol functionArgument;

        @Override
        public MiddlewareRegistrar build() {
            return new MiddlewareRegistrar(this);
        }

        /**
         * Set the name of the MiddlewareRegistrar function.
         *
         * @param resolvedFunction a symbol that resolves to the function .
         * @return Returns the builder.
         */
        public Builder resolvedFunction(Symbol resolvedFunction) {
            this.resolvedFunction = resolvedFunction;
            return this;
        }

        /**
         * Sets the function Argument for the MiddlewareRegistrar function.
         *
         * @param functionArgument A Symbol representing the argument to the middleware register function.
         * @return Returns the builder.
         */
        public Builder functionArgument(Symbol functionArgument) {
            this.functionArgument = functionArgument;
            return this;
        }
    }
}
