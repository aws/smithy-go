/*
 * Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
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

import java.util.Optional;
import java.util.function.Consumer;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.EndpointGenerator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.TriConsumer;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.rulesengine.traits.EndpointRuleSetTrait;

public class SmithyEndpointGenerator implements GoIntegration {

    public static final String PUBLIC_PARAMETERS_NAME = "EndpointParameters";

    @Override
    public void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            TriConsumer<String, String, Consumer<GoWriter>> writerFactory) {

        var serviceShape = settings.getService(model);

        var publicOptionsType = SymbolUtils.createValueSymbolBuilder(PUBLIC_PARAMETERS_NAME).build();

        var generator = EndpointGenerator.builder()
                .publicParametersType(publicOptionsType)
                .build();


        Optional<EndpointRuleSet> rulesetOpt = Optional.empty();
        var ruleSetTraitOpt = serviceShape.getTrait(EndpointRuleSetTrait.class);
        if (ruleSetTraitOpt.isPresent()) {
            rulesetOpt = Optional.of(EndpointRuleSet.fromNode(ruleSetTraitOpt.get().getRuleSet()));
        }

        Optional<EndpointRuleSet> finalRulesetOpt = rulesetOpt;
        writerFactory.accept("endpoints.go", settings.getModuleName(), (w) -> {
                    if (finalRulesetOpt.isPresent()) {
                        w.write("$W", generator.generateParameters(finalRulesetOpt.get()));
                    } else {
                        w.write("$W", generator.generateEmptyParameters());
                    }

                });

    }

}
