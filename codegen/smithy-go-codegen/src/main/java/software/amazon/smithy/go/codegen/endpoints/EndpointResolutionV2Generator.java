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

import java.util.Optional;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.rulesengine.language.EndpointRuleSet;
import software.amazon.smithy.rulesengine.traits.EndpointRuleSetTrait;



/**
 * Generates all components required for Smithy Ruleset Endpoint Resolution.
 * These components include a Provider, Parameters, and Tests.
 */
public class EndpointResolutionV2Generator {

    public static final String FEATURE_NAME = "V2";
    public static final String PARAMETERS_TYPE_NAME = "EndpointParameters";
    public static final String RESOLVER_INTERFACE_NAME = "Endpoint" + "Resolver" + FEATURE_NAME;
    public static final String RESOLVER_IMPLEMENTATION_NAME = "resolver" + FEATURE_NAME;
    public static final String RESOLVER_ENDPOINT_METHOD_NAME = "ResolveEndpoint";
    public static final String NEW_RESOLVER_FUNC_NAME = "NewDefault" + RESOLVER_INTERFACE_NAME;

    private final FnProvider fnProvider;

    public EndpointResolutionV2Generator(FnProvider fnProvider) {
        this.fnProvider = fnProvider;
    }

    public void generate(ProtocolGenerator.GenerationContext context) {

        var serviceShape = context.getService();

        var endpointType = SymbolUtils.createValueSymbolBuilder("Endpoint",
                SmithyGoDependency.SMITHY_ENDPOINTS).build();

        var parametersType = SymbolUtils.createValueSymbolBuilder(PARAMETERS_TYPE_NAME).build();
        var resolverInterfaceType = SymbolUtils.createValueSymbolBuilder(RESOLVER_INTERFACE_NAME).build();
        var resolverImplementationType = SymbolUtils.createValueSymbolBuilder(RESOLVER_IMPLEMENTATION_NAME).build();
        var newResolverFn = SymbolUtils.createValueSymbolBuilder(NEW_RESOLVER_FUNC_NAME).build();

        var parametersGenerator = EndpointParametersGenerator.builder()
                .parametersType(parametersType)
                .build();

        var resolverGenerator = EndpointResolverGenerator.builder()
                .parametersType(parametersType)
                .resolverInterfaceType(resolverInterfaceType)
                .resolverImplementationType(resolverImplementationType)
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
