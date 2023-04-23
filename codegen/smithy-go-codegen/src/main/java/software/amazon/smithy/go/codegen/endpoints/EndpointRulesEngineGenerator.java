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

package software.amazon.smithy.go.codegen.endpoints;

import static software.amazon.smithy.go.codegen.GoWriter.goDocTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;


import java.util.Optional;
import java.util.function.Consumer;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.TriConsumer;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.rulesengine.traits.EndpointRuleSetTrait;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyBuilder;


/**
 * Generates all components required for Smithy Ruleset Endpoint Resolution.
 * These components include a Provider, Parameters, and Tests.
 */
public class EndpointRulesEngineGenerator {

    // make the FnGenerator parameterizable? or something else

    // EndpointRulesEngineGenerator
        // EndpointResolverGenerator
            // ExpressionGenerator
                // FnGenerator




    public static final String PARAMETERS_TYPE_NAME = "EndpointParameters";
    public static final String RESOLVER_TYPE_NAME = "EndpointResolverV2";
    public static final String RESOLVER_ENDPOINT_METHOD_NAME = "ResolveEndpoint";
    public static final String NEW_RESOLVER_FUNC_NAME = "NewDefault" + RESOLVER_TYPE_NAME;

    private final FnProvider fnProvider;

    public EndpointRulesEngineGenerator(FnProvider fnProvider) {
        this.fnProvider = fnProvider;
    }

    public void generate(ProtocolGenerator.GenerationContext context) {

        var serviceShape = context.getService();

        var endpointType = SymbolUtils.createValueSymbolBuilder("Endpoint",
                SmithyGoDependency.SMITHY_TRANSPORT).build();

        var parametersType = SymbolUtils.createValueSymbolBuilder(PARAMETERS_TYPE_NAME).build();
        var resolverType = SymbolUtils.createValueSymbolBuilder(RESOLVER_TYPE_NAME).build();
        var newResolverFn = SymbolUtils.createValueSymbolBuilder(NEW_RESOLVER_FUNC_NAME).build();

        var parametersGenerator = EndpointParametersGenerator.builder()
                .parametersType(parametersType)
                .build();

        var resolverGenerator = EndpointResolverGenerator.builder()
                .parametersType(parametersType)
                .resolverType(resolverType)
                .newResolverFn(newResolverFn)
                .endpointType(endpointType)
                .resolveEndpointMethodName(RESOLVER_ENDPOINT_METHOD_NAME)
                .fnProvider(this.fnProvider)
                .build();


        Optional<EndpointRuleSet> ruleset = serviceShape.getTrait(EndpointRuleSetTrait.class)
                                                    .map(
                                                        (trait)
                                                        -> EndpointRuleSet.fromNode(trait.getRuleSet())
                                                    );

        context.getWriter()
                .map(
                    (writer)
                    -> writer.write("$W", parametersGenerator.generate(ruleset))
                );

        context.getWriter()
                .map(
                    (writer)
                    -> writer.write("$W", resolverGenerator.generate(ruleset))
                );

    }



}
