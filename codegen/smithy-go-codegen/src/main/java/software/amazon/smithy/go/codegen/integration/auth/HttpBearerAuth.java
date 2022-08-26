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

package software.amazon.smithy.go.codegen.integration.auth;

import java.util.List;
import java.util.Map;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.go.codegen.integration.ConfigField;
import software.amazon.smithy.go.codegen.integration.ConfigFieldResolver;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.go.codegen.integration.MiddlewareRegistrar;
import software.amazon.smithy.go.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.ServiceIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.HttpBearerAuthTrait;
import software.amazon.smithy.model.traits.OptionalAuthTrait;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.utils.ListUtils;

/**
 * Integration to add support for httpBearerAuth authentication scheme to an API client.
 */
public class HttpBearerAuth implements GoIntegration {

    public static final String TOKEN_PROVIDER_OPTION_NAME = "BearerAuthTokenProvider";
    private static final String SIGNER_OPTION_NAME = "BearerAuthSigner";
    private static final String NEW_DEFAULT_SIGNER_NAME = "newDefault" + SIGNER_OPTION_NAME;
    private static final String SIGNER_RESOLVER_NAME = "resolve" + SIGNER_OPTION_NAME;
    private static final String REGISTER_MIDDLEWARE_NAME = "add" + SIGNER_OPTION_NAME + "Middleware";

    @Override
    public void writeAdditionalFiles(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            GoDelegator goDelegator
    ) {
        var service = settings.getService(model);
        if (!isSupportedAuthentication(model, service)) {
            return;
        }

        goDelegator.useShapeWriter(service, (writer) -> {
            writeMiddlewareRegister(writer);
            writeSignerConfigFieldResolver(writer);
            writeNewSignerFunc(writer);
        });
    }

    private void writeMiddlewareRegister(GoWriter writer) {
        writer.pushState();

        writer.putContext("funcName", REGISTER_MIDDLEWARE_NAME);
        writer.putContext("stack", SymbolUtils.createValueSymbolBuilder("Stack",
                SmithyGoDependency.SMITHY_MIDDLEWARE).build());
        writer.putContext("addMiddleware", SymbolUtils.createValueSymbolBuilder("AddAuthenticationMiddleware",
                SmithyGoDependency.SMITHY_AUTH_BEARER).build());
        writer.putContext("signerOption", SIGNER_OPTION_NAME);
        writer.putContext("providerOption", TOKEN_PROVIDER_OPTION_NAME);

        writer.write("""
                func $funcName:L(stack *$stack:T, o Options) error {
                    return $addMiddleware:T(stack, o.$signerOption:L, o.$providerOption:L)
                }
                """);

        writer.popState();
    }

    private void writeSignerConfigFieldResolver(GoWriter writer) {
        writer.pushState();

        writer.putContext("funcName", SIGNER_RESOLVER_NAME);
        writer.putContext("signerOption", SIGNER_OPTION_NAME);
        writer.putContext("newDefaultSigner", NEW_DEFAULT_SIGNER_NAME);

        writer.write("""
                func $funcName:L(o *Options) {
                    if o.$signerOption:L != nil {
                        return
                    }
                    o.$signerOption:L = $newDefaultSigner:L(*o)
                }
                """);

        writer.popState();
    }

    private void writeNewSignerFunc(GoWriter writer) {
        writer.pushState();

        writer.putContext("funcName", NEW_DEFAULT_SIGNER_NAME);
        writer.putContext("signerInterface", SymbolUtils.createValueSymbolBuilder("Signer",
                SmithyGoDependency.SMITHY_AUTH_BEARER).build());

        // TODO this is HTTP specific, should be based on protocol/transport of API.
        writer.putContext("newDefaultSigner", SymbolUtils.createValueSymbolBuilder("NewSignHTTPSMessage",
                SmithyGoDependency.SMITHY_AUTH_BEARER).build());

        writer.write("""
                func $funcName:L(o Options) $signerInterface:T {
                    return $newDefaultSigner:T()
                }
                """);

        writer.popState();
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return ListUtils.of(
                RuntimeClientPlugin.builder()
                        .servicePredicate(HttpBearerAuth::isSupportedAuthentication)
                        .addConfigField(ConfigField.builder()
                                .name(TOKEN_PROVIDER_OPTION_NAME)
                                .type(SymbolUtils.createValueSymbolBuilder("TokenProvider",
                                        SmithyGoDependency.SMITHY_AUTH_BEARER).build())
                                .documentation("Bearer token value provider")
                                .build())
                        .build(),
                RuntimeClientPlugin.builder()
                        .servicePredicate(HttpBearerAuth::isSupportedAuthentication)
                        .addConfigField(ConfigField.builder()
                                .name(SIGNER_OPTION_NAME)
                                .type(SymbolUtils.createValueSymbolBuilder("Signer",
                                        SmithyGoDependency.SMITHY_AUTH_BEARER).build())
                                .documentation("Signer for authenticating requests with bearer auth")
                                .build())
                        .addConfigFieldResolver(ConfigFieldResolver.builder()
                                .location(ConfigFieldResolver.Location.CLIENT)
                                .target(ConfigFieldResolver.Target.INITIALIZATION)
                                .resolver(SymbolUtils.createValueSymbolBuilder(SIGNER_RESOLVER_NAME).build())
                                .build())
                        .build(),

                // TODO this is incorrect for an API client/operation that supports multiple auth schemes.
                RuntimeClientPlugin.builder()
                        .operationPredicate(HttpBearerAuth::hasBearerAuthScheme)
                        .registerMiddleware(MiddlewareRegistrar.builder()
                                .resolvedFunction(SymbolUtils.createValueSymbolBuilder(
                                        REGISTER_MIDDLEWARE_NAME).build())
                                .useClientOptions()
                                .build())
                        .build()
        );
    }

    /**
     * Returns if the service has the httpBearerAuth trait.
     *
     * @param model   model definition
     * @param service service shape for the API
     * @return if the httpBearerAuth trait is used by the service
     */
    public static boolean isSupportedAuthentication(Model model, ServiceShape service) {
        return ServiceIndex.of(model).getAuthSchemes(service).values().stream().anyMatch(trait -> trait.getClass()
                .equals(HttpBearerAuthTrait.class));

    }

    /**
     * Returns if the service and operation support the httpBearerAuthTrait.
     *
     * @param model     model definition
     * @param service   service shape for the API
     * @param operation operation shape
     * @return if the service and operation support the httpBearerAuthTrait
     */
    public static boolean hasBearerAuthScheme(Model model, ServiceShape service, OperationShape operation) {
        Map<ShapeId, Trait> auth = ServiceIndex.of(model).getEffectiveAuthSchemes(service.getId(), operation.getId());
        return auth.containsKey(HttpBearerAuthTrait.ID) && !operation.hasTrait(OptionalAuthTrait.class);
    }
}
