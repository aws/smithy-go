/*
 * Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import static software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator.createFinalizeStepMiddleware;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.MiddlewareIdentifier;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.auth.GetIdentityMiddlewareGenerator;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.rulesengine.traits.EndpointRuleSetTrait;
import software.amazon.smithy.utils.MapUtils;

/**
 * Class responsible for generating middleware
 * that will be used during endpoint resolution.
 */
public final class EndpointMiddlewareGenerator {
    public static final String MIDDLEWARE_NAME = "resolveEndpointV2Middleware";
    public static final String MIDDLEWARE_ID = "ResolveEndpointV2";

    private final ProtocolGenerator.GenerationContext context;

    public EndpointMiddlewareGenerator(ProtocolGenerator.GenerationContext context) {
        this.context = context;
    }

    public static GoWriter.Writable generateAddToProtocolFinalizers() {
        return goTemplate("""
                if err := stack.Finalize.Insert(&$L{options: options}, $S, $T); err != nil {
                    return $T("add $L: %v", err)
                }
                """,
                MIDDLEWARE_NAME,
                GetIdentityMiddlewareGenerator.MIDDLEWARE_ID,
                SmithyGoTypes.Middleware.After,
                GoStdlibTypes.Fmt.Errorf,
                MIDDLEWARE_ID);
    }

    public GoWriter.Writable generate() {
        return createFinalizeStepMiddleware(MIDDLEWARE_NAME, MiddlewareIdentifier.string(MIDDLEWARE_ID))
                .asWritable(generateBody(), generateFields());
    }

    private GoWriter.Writable generateFields() {
        return goTemplate("""
                options Options
                """);
    }

    private GoWriter.Writable generateBody() {
        if (!context.getService().hasTrait(EndpointRuleSetTrait.class)) {
            return goTemplate("return next.HandleFinalize(ctx, in)");
        }

        return goTemplate("""
                $pre:W

                $assertRequest:W

                $assertResolver:W

                $resolveEndpoint:W

                $mergeAuthProperties:W

                $post:W

                return next.HandleFinalize(ctx, in)
                """,
                MapUtils.of(
                        "pre", generatePreResolutionHooks(),
                        "assertRequest", generateAssertRequest(),
                        "assertResolver", generateAssertResolver(),
                        "resolveEndpoint", generateResolveEndpoint(),
                        "mergeAuthProperties", generateMergeAuthProperties(),
                        "post", generatePostResolutionHooks()
                ));
    }

    private GoWriter.Writable generatePreResolutionHooks() {
        return (GoWriter writer) -> {
            for (GoIntegration integration : context.getIntegrations()) {
                integration.renderPreEndpointResolutionHook(context.getSettings(), writer, context.getModel());
            }
        };
    }

    private GoWriter.Writable generateAssertRequest() {
        return goTemplate("""
                req, ok := in.Request.($P)
                if !ok {
                    return out, metadata, $T("unknown transport type %T", in.Request)
                }
                """,
                SmithyGoTypes.Transport.Http.Request,
                GoStdlibTypes.Fmt.Errorf);
    }

    private GoWriter.Writable generateAssertResolver() {
        return goTemplate("""
                if m.options.EndpointResolverV2 == nil {
                    return out, metadata, $T("expected endpoint resolver to not be nil")
                }
                """,
                GoStdlibTypes.Fmt.Errorf);
    }

    private GoWriter.Writable generateResolveEndpoint() {
        return goTemplate("""
                params := bindEndpointParams(ctx, getOperationInput(ctx), m.options)
                endpt, err := m.options.EndpointResolverV2.ResolveEndpoint(ctx, *params)
                if err != nil {
                    return out, metadata, $1T("failed to resolve service endpoint, %w", err)
                }

                if endpt.URI.RawPath == "" && req.URL.RawPath != "" {
                    endpt.URI.RawPath = endpt.URI.Path
                }
                req.URL.Scheme = endpt.URI.Scheme
                req.URL.Host = endpt.URI.Host
                req.URL.Path = $2T(endpt.URI.Path, req.URL.Path)
                req.URL.RawPath = $2T(endpt.URI.RawPath, req.URL.RawPath)
                for k := range endpt.Headers {
                    req.Header.Set(k, endpt.Headers.Get(k))
                }
                """,
                GoStdlibTypes.Fmt.Errorf,
                SmithyGoTypes.Transport.Http.JoinPath);
    }

    private GoWriter.Writable generateMergeAuthProperties() {
        return goTemplate("""
                rscheme := getResolvedAuthScheme(ctx)
                if rscheme == nil {
                    return out, metadata, $T("no resolved auth scheme")
                }

                opts, _ := $T(&endpt.Properties)
                for _, o := range opts {
                    rscheme.SignerProperties.SetAll(&o.SignerProperties)
                }
                """, GoStdlibTypes.Fmt.Errorf, SmithyGoTypes.Auth.GetAuthOptions);
    }

    private GoWriter.Writable generatePostResolutionHooks() {
        return (GoWriter writer) -> {
            for (GoIntegration integration : context.getIntegrations()) {
                integration.renderPostEndpointResolutionHook(context.getSettings(), writer, context.getModel());
            }
        };
    }
}
