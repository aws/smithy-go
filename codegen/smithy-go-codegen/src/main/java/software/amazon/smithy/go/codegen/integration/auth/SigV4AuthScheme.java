/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.go.codegen.integration.auth;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SymbolUtils.buildPackageSymbol;

import java.util.List;
import software.amazon.smithy.aws.traits.auth.SigV4ATrait;
import software.amazon.smithy.aws.traits.auth.SigV4Trait;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ChainWritable;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoUniverseTypes;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.auth.AuthParameter;
import software.amazon.smithy.go.codegen.auth.AuthParametersGenerator;
import software.amazon.smithy.go.codegen.auth.AuthParametersResolver;
import software.amazon.smithy.go.codegen.integration.ConfigField;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.go.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.utils.ListUtils;

/**
 * Code generation for SigV4/SigV4A.
 *
 * <p>This integration provides the default, generic SigV4 and SigV4A auth schemes for smithy-go clients: the
 * modeled auth options, default auth schemes backed by the standalone signers in
 * {@code github.com/aws/smithy-go/aws-http-auth-schemes/sigv4} and
 * {@code github.com/aws/smithy-go/aws-http-auth-schemes/sigv4a}, a credentials-backed identity resolver from
 * {@code github.com/aws/smithy-go/aws-http-auth-schemes/identity} shared by both schemes, and the
 * {@code Region}/{@code Credentials} client config they need. AWS SDK clients replace this integration wholesale
 * (see {@link software.amazon.smithy.go.codegen.integration.Replaces}) with one bound to the SDK runtime.
 */
public class SigV4AuthScheme implements GoIntegration {
    private boolean isSigV4XService(Model model, ServiceShape service) {
        return service.hasTrait(SigV4Trait.class) || service.hasTrait(SigV4ATrait.class);
    }

    private boolean isSigV4Service(Model model, ServiceShape service) {
        return service.hasTrait(SigV4Trait.class);
    }

    private boolean isSigV4AService(Model model, ServiceShape service) {
        return service.hasTrait(SigV4ATrait.class);
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return ListUtils.of(
                RuntimeClientPlugin.builder()
                        .servicePredicate(this::isSigV4XService)
                        .addAuthParameter(AuthParameter.REGION)
                        .addConfigField(ConfigField.builder()
                                .name("Region")
                                .type(GoUniverseTypes.String)
                                .documentation("The region to sign requests for.")
                                .build())
                        .addConfigField(ConfigField.builder()
                                .name("Credentials")
                                .type(SmithyGoDependency.SMITHY_HTTP_AUTH_IDENTITY.interfaceSymbol("AWSCredentialIdentityResolver"))
                                .documentation("The credentials provider used to sign SigV4/SigV4A requests.")
                                .build())
                        .addAuthParameterResolver(
                                new AuthParametersResolver(buildPackageSymbol("bindAuthParamsRegion")))
                        .build(),
                RuntimeClientPlugin.builder()
                        .servicePredicate(this::isSigV4Service)
                        .addAuthSchemeDefinition(SigV4Trait.ID, new DefaultSigV4())
                        .build(),
                RuntimeClientPlugin.builder()
                        .servicePredicate(this::isSigV4AService)
                        .addAuthSchemeDefinition(SigV4ATrait.ID, new DefaultSigV4A())
                        .build()
        );
    }

    @Override
    public void writeAdditionalFiles(
            GoSettings settings, Model model, SymbolProvider symbolProvider, GoDelegator goDelegator
    ) {
        if (isSigV4XService(model, settings.getService(model))) {
            goDelegator.useFileWriter("auth.go", settings.getModuleName(), generateAdditionalSource());
        }
    }

    /**
     * Default SigV4 auth scheme definition for generic clients. Extends the shared modeled-option generation with a
     * default scheme and identity resolver wired to the smithy-go SigV4 runtime.
     */
    public static class DefaultSigV4 extends SigV4Definition {
        @Override
        public Writable generateDefaultAuthScheme() {
            return goTemplate("$T()",
                    SmithyGoDependency.SMITHY_HTTP_SIGV4.func("NewAuthScheme"));
        }

        @Override
        public Writable generateOptionsIdentityResolver() {
            return goTemplate("getSigV4IdentityResolver(o)");
        }
    }

    /**
     * Default SigV4A auth scheme definition for generic clients. Extends the shared modeled-option generation with a
     * default scheme and identity resolver wired to the smithy-go SigV4A runtime.
     */
    public static class DefaultSigV4A extends SigV4ADefinition {
        @Override
        public Writable generateDefaultAuthScheme() {
            return goTemplate("$T()",
                    SmithyGoDependency.SMITHY_HTTP_SIGV4A.func("NewAuthScheme"));
        }

        @Override
        public Writable generateOptionsIdentityResolver() {
            return goTemplate("getSigV4IdentityResolver(o)");
        }
    }

    private Writable generateAdditionalSource() {
        return ChainWritable.of(
                generateGetIdentityResolver(),
                generateRegionResolver()
        ).compose();
    }

    private Writable generateGetIdentityResolver() {
        return goTemplate("""
                func getSigV4IdentityResolver(o Options) $T {
                    if o.Credentials != nil {
                        return $T(o.Credentials)
                    }

                    return nil
                }
                """,
                SmithyGoDependency.SMITHY_AUTH.interfaceSymbol("IdentityResolver"),
                SmithyGoDependency.SMITHY_HTTP_AUTH_IDENTITY.func("NewIdentityResolver"));
    }

    private Writable generateRegionResolver() {
        return goTemplate("""
                func bindAuthParamsRegion(_ interface{}, params $P, _ interface{}, options Options) error {
                    params.Region = options.Region
                    return nil
                }
                """, AuthParametersGenerator.STRUCT_SYMBOL);
    }
}
