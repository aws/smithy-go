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

package software.amazon.smithy.go.codegen.integration.auth;

import java.util.HashMap;
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
import software.amazon.smithy.go.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.ServiceIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.HttpApiKeyAuthTrait;
import software.amazon.smithy.model.traits.OptionalAuthTrait;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.MapUtils;

/**
 * Integration to add support for httpApiKeyAuth authentication scheme to an API client.
 */
public class HttpApiKeyAuth implements GoIntegration {

    public static final String API_KEY_PROVIDER_OPTION_NAME = "ApiKeyAuthProvider";
    private static final String SIGNER_OPTION_NAME = "ApiKeyAuthSigner";
    private static final String NEW_DEFAULT_SIGNER_NAME = "newDefault" + SIGNER_OPTION_NAME;
    private static final String SIGNER_RESOLVER_NAME = "resolve" + SIGNER_OPTION_NAME;
    public static final String REGISTER_MIDDLEWARE_NAME = "add" + SIGNER_OPTION_NAME + "Middleware";

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

        Map<String, Object> authDefinition = new HashMap<String, Object>();
        authDefinition.put("in", service.expectTrait(HttpApiKeyAuthTrait.class).getIn().toString());
        authDefinition.put("name", service.expectTrait(HttpApiKeyAuthTrait.class).getName());
        service.expectTrait(HttpApiKeyAuthTrait.class).getScheme().ifPresent(scheme ->
            authDefinition.put("scheme", scheme));

        goDelegator.useShapeWriter(service, (writer) -> {
            writeMiddlewareRegister(writer, authDefinition);
            writeSignerConfigFieldResolver(writer);
            writeNewSignerFunc(writer);
        });
    }

    private void writeMiddlewareRegister(GoWriter writer, Map<String, Object> authDefinition) {
        writer.pushState();

        Map<String, Object> commonArgs = MapUtils.of(
                "funcName", REGISTER_MIDDLEWARE_NAME,
                "stack", SymbolUtils.createValueSymbolBuilder("Stack", SmithyGoDependency.SMITHY_MIDDLEWARE).build(),
                "addMiddleware", SymbolUtils.createValueSymbolBuilder(
                    "AddAuthenticationMiddleware", SmithyGoDependency.SMITHY_AUTH_APIKEY).build(),
                "signerOption", SIGNER_OPTION_NAME,
                "providerOption", API_KEY_PROVIDER_OPTION_NAME,
                "authDefinition", SymbolUtils.createValueSymbolBuilder(
                    "HttpAuthDefinition", SmithyGoDependency.SMITHY_AUTH).build()
        );

        writer.writeGoBlockTemplate("func $funcName:L(stack *$stack:T, o Options) error {", "}",
            commonArgs,
            (ww) -> {
                ww.writeGoBlockTemplate(
                    "return $addMiddleware:T(stack, o.$signerOption:L, o.$providerOption:L, $authDefinition:T{",
                    "})",
                    authDefinition,
                        (w) -> {
                            w.write("In: $in:S,");
                            w.write("Name: $name:S,");
                            if (authDefinition.containsKey("scheme")) {
                                w.write("Scheme: $scheme:S,");
                            }
                        });
            });


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
                SmithyGoDependency.SMITHY_AUTH_APIKEY).build());

        // TODO this is HTTP specific, should be based on protocol/transport of API.
        writer.putContext("newDefaultSigner", SymbolUtils.createValueSymbolBuilder("NewSignMessage",
                SmithyGoDependency.SMITHY_AUTH_APIKEY).build());

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
                        .servicePredicate(HttpApiKeyAuth::isSupportedAuthentication)
                        .addConfigField(ConfigField.builder()
                                .name(API_KEY_PROVIDER_OPTION_NAME)
                                .type(SymbolUtils.createValueSymbolBuilder("ApiKeyProvider",
                                        SmithyGoDependency.SMITHY_AUTH_APIKEY).build())
                                .documentation("API key provider")
                                .build())
                        .build(),
                RuntimeClientPlugin.builder()
                        .servicePredicate(HttpApiKeyAuth::isSupportedAuthentication)
                        .addConfigField(ConfigField.builder()
                                .name(SIGNER_OPTION_NAME)
                                .type(SymbolUtils.createValueSymbolBuilder("Signer",
                                        SmithyGoDependency.SMITHY_AUTH_APIKEY).build())
                                .documentation("Signer for authenticating requests with api key auth")
                                .build())
                        .addConfigFieldResolver(ConfigFieldResolver.builder()
                                .location(ConfigFieldResolver.Location.CLIENT)
                                .target(ConfigFieldResolver.Target.INITIALIZATION)
                                .resolver(SymbolUtils.createValueSymbolBuilder(SIGNER_RESOLVER_NAME).build())
                                .build())
                        .build()
        );
    }

    /**
     * Returns if the service has the httpApiKeyAuth trait.
     *
     * @param model   model definition
     * @param service service shape for the API
     * @return if the httpApiKeyAuth trait is used by the service
     */
    public static boolean isSupportedAuthentication(Model model, ServiceShape service) {
        return ServiceIndex.of(model).getAuthSchemes(service).values().stream().anyMatch(trait -> trait.getClass()
                .equals(HttpApiKeyAuthTrait.class));

    }

    /**
     * Returns if the service and operation support the httpApiKeyAuthTrait.
     *
     * @param model     model definition
     * @param service   service shape for the API
     * @param operation operation shape
     * @return if the service and operation support the httpApiKeyAuthTrait
     */
    public static boolean hasApiKeyAuthScheme(Model model, ServiceShape service, OperationShape operation) {
        Map<ShapeId, Trait> auth = ServiceIndex.of(model).getEffectiveAuthSchemes(service.getId(), operation.getId());
        return auth.containsKey(HttpApiKeyAuthTrait.ID) && !operation.hasTrait(OptionalAuthTrait.class);
    }
}
