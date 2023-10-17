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

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.MiddlewareIdentifier;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
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
    public static final String ADD_FUNC_NAME = "addResolveEndpointV2Middleware";

    private final ProtocolGenerator.GenerationContext context;

    public EndpointMiddlewareGenerator(ProtocolGenerator.GenerationContext context) {
        this.context = context;
    }

    public GoWriter.Writable generate() {
        if (!context.getService().hasTrait(EndpointRuleSetTrait.class)) {
            return goTemplate("");
        }

        return goTemplate("""
                $W

                $W
                """,
                generateMiddleware(),
                generateAddFunc());
    }

    private GoWriter.Writable generateMiddleware() {
        return writer -> {
            GoStackStepMiddlewareGenerator
                    .createSerializeStepMiddleware(MIDDLEWARE_NAME, MiddlewareIdentifier.string(MIDDLEWARE_ID))
                    .writeMiddleware(writer, this::generateBody, this::generateFields);
        };
    }

    private void generateFields(GoStackStepMiddlewareGenerator generator, GoWriter writer) {
        writer.writeGoTemplate("""
                options Options
                """);
    }

    private void generateBody(GoStackStepMiddlewareGenerator generator, GoWriter writer) {
        writer.writeGoTemplate("""
                $pre:W

                $assertRequest:W

                $assertResolver:W

                $resolveEndpoint:W

                $post:W

                return next.HandleSerialize(ctx, in)
                """,
                MapUtils.of(
                        "pre", generatePreResolutionHooks(),
                        "assertRequest", generateAssertRequest(),
                        "assertResolver", generateAssertResolver(),
                        "resolveEndpoint", generateResolveEndpoint(),
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
                params := bindEndpointParams(in.Parameters, m.options)
                resolvedEndpoint, err := m.options.EndpointResolverV2.ResolveEndpoint(ctx, *params)
                if err != nil {
                    return out, metadata, $T("failed to resolve service endpoint, %w", err)
                }

                req.URL = &resolvedEndpoint.URI
                for k := range resolvedEndpoint.Headers {
                    req.Header.Set(k, resolvedEndpoint.Headers.Get(k))
                }
                """,
                GoStdlibTypes.Fmt.Errorf);
    }

    private GoWriter.Writable generatePostResolutionHooks() {
        return (GoWriter writer) -> {
            for (GoIntegration integration : context.getIntegrations()) {
                integration.renderPostEndpointResolutionHook(context.getSettings(), writer, context.getModel());
            }
        };
    }

    private GoWriter.Writable generateAddFunc() {
        return goTemplate("""
                func $funcName:L(stack $stack:P, options Options) error {
                    return stack.Serialize.Insert(&$structName:L{
                        options: options,
                    }, "ResolveEndpoint", middleware.After)
                }
                """,
                MapUtils.of(
                        "funcName", ADD_FUNC_NAME,
                        "structName", MIDDLEWARE_NAME,
                        "stack", SmithyGoTypes.Middleware.Stack
                ));
    }
}
