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

package software.amazon.smithy.go.codegen.endpoints;

import static software.amazon.smithy.go.codegen.endpoints.EndpointRulesGenerator.DEFAULT_NEW_RESOLVER_FUNC_NAME;
import static software.amazon.smithy.go.codegen.endpoints.EndpointRulesGenerator.DEFAULT_OPTIONS_NAME;
import static software.amazon.smithy.go.codegen.endpoints.EndpointRulesGenerator.DEFAULT_RESOLVER_TYPE_NAME;
import static software.amazon.smithy.go.codegen.endpoints.EndpointRulesGenerator.DEFAULT_RESOLVE_ENDPOINT_METHOD_NAME;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.GoWriterDelegator;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.rulesengine.testutil.TestDiscovery;
import software.amazon.smithy.utils.SmithyBuilder;

/**
 * Generator for ReterminusCore's test endpoint rules and test cases.
 */
public final class RuleEngineTestSuiteGenerator {
    private final GoWriterDelegator writerFactory;
    private final String moduleName;

    private RuleEngineTestSuiteGenerator(Builder builder) {
        writerFactory = SmithyBuilder.requiredState("writerFactory", builder.writerFactory);
        moduleName = SmithyBuilder.requiredState("moduleName", builder.moduleName);
    }

    public String getModuleName() {
        return moduleName;
    }

    public void generateTestSuites(Stream<TestDiscovery.RulesTestSuite> testSuites) {
        AtomicInteger counter = new AtomicInteger();
        testSuites.forEach(testSuite -> {
            var c = counter.getAndIncrement();

            var packageName = "test_suite_" + c;
            var importPath = moduleName + "/" + packageName;

            var parametersType = getPackageSymbol(importPath, DEFAULT_OPTIONS_NAME).build();
            var resolverType = getPackageSymbol(importPath, DEFAULT_RESOLVER_TYPE_NAME).build();
            var newResolverType = getPackageSymbol(importPath, DEFAULT_NEW_RESOLVER_FUNC_NAME).build();
            var endpointType = SymbolUtils.createValueSymbolBuilder("Endpoint",
                    SmithyGoDependency.SMITHY_TRANSPORT).build();

            var paramsGenerator = EndpointParametersGenerator.builder()
                    .parametersType(parametersType)
                    .build();
            var ruleGenerator = EndpointRulesGenerator.builder()
                    .parametersType(parametersType)
                    .resolverType(resolverType)
                    .newResolverFn(newResolverType)
                    .endpointType(endpointType)
                    .resolveEndpointMethodName(DEFAULT_RESOLVE_ENDPOINT_METHOD_NAME)
                    .build();

            var ruleset = testSuite.ruleset();
            writerFactory.useFileWriter(packageName + "/smithy_endpoints.go", importPath, (w) -> {
                w.write("$W", paramsGenerator.generate(ruleset.getParameters()));
                w.write("$W", ruleGenerator.generate(ruleset));
            });

            var ruleTestGenerator = EndpointTestsGenerator.builder()
                    .parametersType(parametersType)
                    .resolverType(resolverType)
                    .newResolverFn(newResolverType)
                    .endpointType(endpointType)
                    .resolveEndpointMethodName(DEFAULT_RESOLVE_ENDPOINT_METHOD_NAME)
                    .build();

            var testCases = testSuite.testSuite().getTestCases();
            writerFactory.useFileWriter(packageName + "/smithy_endpoints_test.go", importPath, (w) -> {
                w.write("$W", ruleTestGenerator.generate(ruleset.getParameters().toList(), testCases));
            });
        });
    }

    private Symbol.Builder getPackageSymbol(String importPath, String name) {
        return SymbolUtils.getPackageSymbol(importPath, name, "internalendpointtest", false);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for ReterminusCoreTestGenerator.
     */
    public static class Builder implements SmithyBuilder<RuleEngineTestSuiteGenerator> {
        private GoWriterDelegator writerFactory;
        private String moduleName;

        /**
         * Sets module name.
         *
         * @param moduleName name of module to generate into
         * @return builder
         */
        public Builder moduleName(String moduleName) {
            this.moduleName = moduleName;
            return this;
        }

        /**
         * Sets writer factory.
         *
         * @param writerFactory writer factory
         * @return builder
         */
        public Builder writerFactory(GoWriterDelegator writerFactory) {
            this.writerFactory = writerFactory;
            return this;
        }

        @Override
        public RuleEngineTestSuiteGenerator build() {
            return new RuleEngineTestSuiteGenerator(this);
        }
    }
}
