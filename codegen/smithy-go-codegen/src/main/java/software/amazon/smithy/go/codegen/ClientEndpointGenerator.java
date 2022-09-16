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

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goDocTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.joinWritables;

import java.util.Arrays;
import java.util.List;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.endpoints.EndpointParametersGenerator;
import software.amazon.smithy.go.codegen.endpoints.EndpointRulesGenerator;
import software.amazon.smithy.go.codegen.endpoints.EndpointTestsGenerator;
import software.amazon.smithy.rulesengine.language.EndpointRuleset;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameter;
import software.amazon.smithy.rulesengine.traits.EndpointTestCase;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyBuilder;

public final class ClientEndpointGenerator {
    private final Symbol endpointType;
    private final Symbol publicOptionsType;
    private final Symbol publicResolverType;
    private final Symbol internalOptionsType;
    private final Symbol internalResolverType;
    private final Symbol internalNewResolverFn;
    private final String resolveEndpointMethodName;

    private final EndpointParametersGenerator parametersGenerator;
    private final EndpointRulesGenerator rulesGenerator;
    private final EndpointTestsGenerator testsGenerator;

    private ClientEndpointGenerator(Builder builder) {
        endpointType = SmithyBuilder.requiredState("endpointType", builder.endpointType);
        publicOptionsType = SmithyBuilder.requiredState("publicOptionsType", builder.publicOptionsType);
        publicResolverType = SmithyBuilder.requiredState("publicResolverType", builder.publicResolverType);
        internalOptionsType = SmithyBuilder.requiredState("parametersType", builder.internalOptionsType);
        internalResolverType = SmithyBuilder.requiredState("internalResolverType", builder.internalResolverType);
        internalNewResolverFn = SmithyBuilder.requiredState("internalNewResolverFn", builder.internalNewResolverFn);
        resolveEndpointMethodName = SmithyBuilder.requiredState("resolveEndpointMethodName",
                builder.resolveEndpointMethodName);

        parametersGenerator = EndpointParametersGenerator.builder()
                .parametersType(internalOptionsType)
                .build();

        rulesGenerator = EndpointRulesGenerator.builder()
                .parametersType(internalOptionsType)
                .resolverType(internalResolverType)
                .newResolverFn(internalNewResolverFn)
                .endpointType(endpointType)
                .resolveEndpointMethodName(resolveEndpointMethodName)
                .build();

        testsGenerator = EndpointTestsGenerator.builder()
                .parametersType(internalOptionsType)
                .resolverType(internalResolverType)
                .newResolverFn(internalNewResolverFn)
                .endpointType(endpointType)
                .resolveEndpointMethodName(resolveEndpointMethodName)
                .build();
    }

    public GoWriter.Writable generatePublicResolverTypes() {
        return goTemplate("""
                        // $publicOptionsType:T is the v2 endpoint resolver set of options.
                        type $publicOptionsType:T = $internalOptionsType:T

                        // $publicResolverType:T provides the interface for resolving service endpoints.
                        type $publicResolverType:T interface {
                            $resolveEndpointMethodDocs:W
                            $resolveEndpointMethod:L(ctx $context:T, options $publicOptionsType:T) (
                                $endpointType:T, error,
                            )
                        }
                        var _ $publicResolverType:T = $internalNewResolverFn:T()
                        """,
                MapUtils.of(
                        "context", SymbolUtils.createValueSymbolBuilder("Context", SmithyGoDependency.CONTEXT).build(),
                        "endpointType", endpointType,
                        "publicOptionsType", publicOptionsType,
                        "publicResolverType", publicResolverType,
                        "internalOptionsType", internalOptionsType,
                        "internalResolverType", internalResolverType,
                        "internalNewResolverFn", internalNewResolverFn,
                        "resolveEndpointMethodDocs", goDocTemplate("$methodName:L returns the endpoint resolved for the"
                                        + " options provided. Returns error if unable to resolve an endpoint.",
                                MapUtils.of(
                                        "methodName", resolveEndpointMethodName
                                )),
                        "resolveEndpointMethod", resolveEndpointMethodName
                ));
    }

    public GoWriter.Writable generateInternalEndpoints(EndpointRuleset ruleset) {
        return joinWritables(Arrays.asList(
                parametersGenerator.generate(ruleset.getParameters()),
                rulesGenerator.generate(ruleset)
        ), "\n\n");
    }

    public GoWriter.Writable generateEmptyInternalEndpoints() {
        return joinWritables(Arrays.asList(
                parametersGenerator.generateEmptyParameters(),
                rulesGenerator.generateEmptyRules()
        ), "\n\n");
    }

    public GoWriter.Writable generateInternalEndpointTests(
            List<Parameter> parameters, List<EndpointTestCase> testCases
    ) {
        return testsGenerator.generate(parameters, testCases);
    }

    public GoWriter.Writable generateClientEndpointTests(List<EndpointTestCase> testCases) {
        // TODO implement client endpoint test generation
        return emptyGoTemplate();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements SmithyBuilder<ClientEndpointGenerator> {
        private Symbol endpointType;
        private Symbol publicOptionsType;
        private Symbol publicResolverType;
        private Symbol internalOptionsType;
        private Symbol internalResolverType;
        private Symbol internalNewResolverFn;
        private String resolveEndpointMethodName;

        public Builder endpointType(Symbol endpointType) {
            this.endpointType = endpointType;
            return this;
        }

        public Builder publicOptionsType(Symbol publicOptionsType) {
            this.publicOptionsType = publicOptionsType;
            return this;
        }

        public Builder publicResolverType(Symbol publicResolverType) {
            this.publicResolverType = publicResolverType;
            return this;
        }

        public Builder internalOptionsType(Symbol internalOptionsType) {
            this.internalOptionsType = internalOptionsType;
            return this;
        }

        public Builder internalResolverType(Symbol internalResolverType) {
            this.internalResolverType = internalResolverType;
            return this;
        }

        public Builder internalNewResolverFn(Symbol internalNewResolverFn) {
            this.internalNewResolverFn = internalNewResolverFn;
            return this;
        }

        public Builder resolveEndpointMethodName(String resolveEndpointMethodName) {
            this.resolveEndpointMethodName = resolveEndpointMethodName;
            return this;
        }

        @Override
        public ClientEndpointGenerator build() {
            return new ClientEndpointGenerator(this);
        }
    }
}
