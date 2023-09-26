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

import java.util.Optional;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
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

                $W
                """,
                generateType(),
                generateMethods(),
                generateAddFunc());
    }

    private GoWriter.Writable generateType() {
        return goTemplate("""
                type $L struct {
                    options Options
                    resolver EndpointResolverV2
                }
                """,
                MIDDLEWARE_NAME);
    }

    private GoWriter.Writable generateMethods() {
        return goTemplate("""
                func (*$name:L) ID() string {
                    return $id:S
                }

                func (m *$name:L) HandleSerialize(ctx $ctxSym:T, in $inSym:T, next $nextSym:T) (
                    out $outSym:T, md $mdSym:T, err error,
                ) {
                    $handleSerializeBody:W
                }
                """,
                MapUtils.of(
                        "name", MIDDLEWARE_NAME,
                        "id", MIDDLEWARE_ID,
                        "ctxSym", GoStdlibTypes.Context.Context,
                        "inSym", SmithyGoTypes.Middleware.SerializeInput,
                        "nextSym", SmithyGoTypes.Middleware.SerializeHandler,
                        "outSym", SmithyGoTypes.Middleware.SerializeOutput,
                        "mdSym", SmithyGoTypes.Middleware.Metadata,
                        "handleSerializeBody", generateHandleSerializeBody()
                ));
    }

    private GoWriter.Writable generateHandleSerializeBody() {
        return goTemplate("""
                $preEndpointResolutionHook:W

                $requestValidator:W

                $legacyResolverValidator:W

                $endpointResolution:W

                $postEndpointResolution:W

                return next.HandleSerialize(ctx, in)
                """,
                MapUtils.of(
                    "preEndpointResolutionHook", generatePreEndpointResolutionHook(),
                    "requestValidator", generateRequestValidator(),
                    "legacyResolverValidator", generateLegacyResolverValidator(),
                    "endpointResolution", generateEndpointResolution(),
                    "postEndpointResolution", generatePostEndpointResolutionHook()
                ));
    }

    private GoWriter.Writable generatePreEndpointResolutionHook() {
        return (GoWriter writer) -> {
            for (GoIntegration integration : context.getIntegrations()) {
                integration.renderPreEndpointResolutionHook(context.getSettings(), writer, context.getModel());
            }
        };
    }

    private GoWriter.Writable generateRequestValidator() {
        return goTemplate("""
                req, ok := in.Request.($P)
                if !ok {
                    return out, md, $T("unknown transport type %T", in.Request)
                }
                """,
                SmithyGoTypes.Transport.Http.Request,
                GoStdlibTypes.Fmt.Errorf);
    }

    private GoWriter.Writable generateLegacyResolverValidator() {
        return goTemplate("""
                if m.resolver == nil {
                    return out, md, $T("expected endpoint resolver to not be nil")
                }
                """,
                GoStdlibTypes.Fmt.Errorf);
    }

    private GoWriter.Writable generateEndpointResolution() {
        return goTemplate("""
                params := bindEndpointParams(in.Parameters.(endpointParamsBinder), m.options)
                resolvedEndpoint, err := m.resolver.ResolveEndpoint(ctx, *params)
                if err != nil {
                    return out, md, $T("failed to resolve service endpoint, %w", err)
                }

                req.URL = &resolvedEndpoint.URI
                for k := range resolvedEndpoint.Headers {
                    req.Header.Set(k, resolvedEndpoint.Headers.Get(k))
                }
                """,
                GoStdlibTypes.Fmt.Errorf);
    }

    private GoWriter.Writable generatePostEndpointResolutionHook() {
        return (GoWriter writer) -> {
            for (GoIntegration integration : context.getIntegrations()) {
                // TODO: refactor op-dependent hooks since this is now a single middleware
                integration.renderPostEndpointResolutionHook(
                        context.getSettings(), writer, context.getModel(), Optional.empty());
            }
        };
    }

    private GoWriter.Writable generateAddFunc() {
        return goTemplate("""
                func $funcName:L(stack $stack:P, options Options) error {
                    return stack.Serialize.Insert(&$structName:L{
                        options: options,
                        resolver: options.EndpointResolverV2,
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
