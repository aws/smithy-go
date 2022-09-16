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

import static software.amazon.smithy.go.codegen.endpoints.EndpointRulesGenerator.DEFAULT_NEW_RESOLVER_FUNC_NAME;
import static software.amazon.smithy.go.codegen.endpoints.EndpointRulesGenerator.DEFAULT_OPTIONS_NAME;
import static software.amazon.smithy.go.codegen.endpoints.EndpointRulesGenerator.DEFAULT_RESOLVER_TYPE_NAME;
import static software.amazon.smithy.go.codegen.endpoints.EndpointRulesGenerator.DEFAULT_RESOLVE_ENDPOINT_METHOD_NAME;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ClientEndpointGenerator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.TriConsumer;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.rulesengine.language.EndpointRuleset;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameter;
import software.amazon.smithy.rulesengine.traits.EndpointRuleSetTrait;
import software.amazon.smithy.rulesengine.traits.EndpointTestCase;
import software.amazon.smithy.rulesengine.traits.EndpointTestsTrait;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.SetUtils;

public class SmithyClientEndpointGenerator implements GoIntegration {

    public static final String PUBLIC_RESOLVER_NAME = "ClientEndpointResolver";
    public static final String PUBLIC_OPTIONS_NAME = "ClientEndpointOptions";

    private static final Logger LOGGER = Logger.getLogger(SmithyClientEndpointGenerator.class.getName());

    private static final String INTERNAL_ENDPOINT_PACKAGE = "internal/endpoints";


    @Override
    public void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            TriConsumer<String, String, Consumer<GoWriter>> writerFactory
    ) {
        var serviceShape = settings.getService(model);

        var endpointType = SymbolUtils.createValueSymbolBuilder("Endpoint",
                SmithyGoDependency.SMITHY_TRANSPORT).build();

        var publicOptionsType = SymbolUtils.createValueSymbolBuilder(PUBLIC_OPTIONS_NAME).build();
        var publicResolverType = SymbolUtils.createValueSymbolBuilder(PUBLIC_RESOLVER_NAME).build();
        var internalOptionsType = getPackageSymbol(settings, DEFAULT_OPTIONS_NAME).build();
        var internalResolverType = getPackageSymbol(settings, DEFAULT_RESOLVER_TYPE_NAME).build();
        var internalNewResolverFn = getPackageSymbol(settings, DEFAULT_NEW_RESOLVER_FUNC_NAME).build();

        var generator = ClientEndpointGenerator.builder()
                .endpointType(endpointType)
                .publicOptionsType(publicOptionsType)
                .publicResolverType(publicResolverType)
                .internalOptionsType(internalOptionsType)
                .internalResolverType(internalResolverType)
                .internalNewResolverFn(internalNewResolverFn)
                .resolveEndpointMethodName(DEFAULT_RESOLVE_ENDPOINT_METHOD_NAME)
                .build();

        writerFactory.accept("endpoints.go", settings.getModuleName(), (w) -> {
            w.write("$W", generator.generatePublicResolverTypes());
        });

        Optional<EndpointRuleset> rulesetOpt = Optional.empty();
        var ruleSetTraitOpt = serviceShape.getTrait(EndpointRuleSetTrait.class);
        if (ruleSetTraitOpt.isPresent()) {
            rulesetOpt = Optional.of(EndpointRuleset.fromNode(ruleSetTraitOpt.get().getRuleSet()));
        }

        Optional<EndpointRuleset> finalRulesetOpt = rulesetOpt;
        writerFactory.accept(INTERNAL_ENDPOINT_PACKAGE + "/smithy_endpoints.go",
                getInternalEndpointImportPath(settings), (w) -> {
                    if (finalRulesetOpt.isPresent()) {
                        w.write("$W", generator.generateInternalEndpoints(finalRulesetOpt.get()));
                    } else {
                        LOGGER.warning("service does not have modeled endpoint rules " + serviceShape.getId());
                        w.write("$W", generator.generateEmptyInternalEndpoints());
                    }

                });

        var endpointTestTraitOpt = serviceShape.getTrait(EndpointTestsTrait.class);
        final List<EndpointTestCase> testCases = new ArrayList<>();
        endpointTestTraitOpt.ifPresent(trait -> testCases.addAll(trait.getTestCases()));

        // Test file needs to be in API client package, so it can generate operation input parameter helpers.
        writerFactory.accept(INTERNAL_ENDPOINT_PACKAGE + "/smithy_endpoints_test.go",
                getInternalEndpointImportPath(settings), (w) -> {
                    List<Parameter> parameters = (finalRulesetOpt.isPresent())
                            ? finalRulesetOpt.get().getParameters().toList() : new ArrayList<>();

                    w.write("$W", generator.generateInternalEndpointTests(parameters, testCases));
                });

        writerFactory.accept("smithy_endpoints_test.go",
                settings.getModuleName(), (w) -> {
                    w.write("$W", generator.generateClientEndpointTests(testCases));
                });
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return ListUtils.of(
                RuntimeClientPlugin.builder()
                        .configFields(SetUtils.of(
                                ConfigField.builder()
                                        .name(PUBLIC_RESOLVER_NAME)
                                        .type(SymbolUtils.createValueSymbolBuilder(PUBLIC_RESOLVER_NAME)
                                                .build())
                                        .documentation("The service Smithy client endpoint resolver.")
                                        .withHelper(true)
                                        .build(),
                                ConfigField.builder()
                                        .name(PUBLIC_OPTIONS_NAME)
                                        .type(SymbolUtils.createValueSymbolBuilder(PUBLIC_OPTIONS_NAME)
                                                .build())
                                        .documentation("The Smithy client endpoint options to be used when attempting "
                                                + "to resolve an endpoint.")
                                        .build()
                        ))
                        // TODO resolver for Reterminus Endpoint handling.
                        .build());
    }

    private String getInternalEndpointImportPath(GoSettings settings) {
        return settings.getModuleName() + "/" + INTERNAL_ENDPOINT_PACKAGE;
    }

    private Symbol.Builder getPackageSymbol(GoSettings settings, String symbolName) {
        return SymbolUtils.getPackageSymbol(getInternalEndpointImportPath(settings), symbolName, "internalendpoint",
                false);
    }
}
