/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import static software.amazon.smithy.go.codegen.GoWriter.joinWritables;

import java.util.Arrays;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.endpoints.EndpointParametersGenerator;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.utils.SmithyBuilder;


public final class EndpointGenerator {
    private final Symbol publicParametersType;

    private final EndpointParametersGenerator parametersGenerator;

    private EndpointGenerator(Builder builder) {
        publicParametersType = SmithyBuilder.requiredState("publicParametersType", builder.publicParametersType);

        parametersGenerator = EndpointParametersGenerator.builder()
                .parametersType(publicParametersType)
                .build();
    }

    public GoWriter.Writable generateParameters(EndpointRuleSet ruleset) {
        return joinWritables(Arrays.asList(
                parametersGenerator.generate(ruleset.getParameters())), "\n\n");
    }

    public GoWriter.Writable generateEmptyParameters() {
        return joinWritables(Arrays.asList(
                parametersGenerator.generateEmptyParameters()), "\n\n");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements SmithyBuilder<EndpointGenerator> {
        private Symbol publicParametersType;

        public Builder publicParametersType(Symbol publicParametersType) {
            this.publicParametersType = publicParametersType;
            return this;
        }

        @Override
        public EndpointGenerator build() {
            return new EndpointGenerator(this);
        }
    }
}
