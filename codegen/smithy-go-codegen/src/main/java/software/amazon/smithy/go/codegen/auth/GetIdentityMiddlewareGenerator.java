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

package software.amazon.smithy.go.codegen.auth;

import static software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator.createFinalizeStepMiddleware;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.MiddlewareIdentifier;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.utils.MapUtils;

public class GetIdentityMiddlewareGenerator {
    public static final String MIDDLEWARE_NAME = "getIdentityMiddleware";
    public static final String MIDDLEWARE_ID = "GetIdentity";

    private final ProtocolGenerator.GenerationContext context;

    public GetIdentityMiddlewareGenerator(ProtocolGenerator.GenerationContext context) {
        this.context = context;
    }

    public static GoWriter.Writable generateAddToProtocolFinalizers() {
        return goTemplate("""
                if err := stack.Finalize.Insert(&$L{options: options}, $S, $T); err != nil {
                    return $T("add $L: %v", err)
                }
                """,
                MIDDLEWARE_NAME,
                ResolveAuthSchemeMiddlewareGenerator.MIDDLEWARE_ID,
                SmithyGoTypes.Middleware.After,
                GoStdlibTypes.Fmt.Errorf,
                MIDDLEWARE_ID);
    }

    public GoWriter.Writable generate() {
        return GoWriter.ChainWritable.of(
                createFinalizeStepMiddleware(MIDDLEWARE_NAME, MiddlewareIdentifier.string(MIDDLEWARE_ID))
                        .asWritable(generateBody(), generateFields()),
                generateContextFuncs()
        ).compose();
    }

    private GoWriter.Writable generateFields() {
        return goTemplate("""
                options Options
                """);
    }

    private GoWriter.Writable generateBody() {
        return goTemplate("""
                rscheme := getResolvedAuthScheme(ctx)
                if rscheme == nil {
                    return out, metadata, $errorf:T("no resolved auth scheme")
                }

                resolver := rscheme.Scheme.IdentityResolver(m.options)
                if resolver == nil {
                    return out, metadata, $errorf:T("no identity resolver")
                }

                identity, err := resolver.GetIdentity(ctx, rscheme.IdentityProperties)
                if err != nil {
                    return out, metadata, $errorf:T("get identity: %w", err)
                }

                ctx = setIdentity(ctx, identity)
                return next.HandleFinalize(ctx, in)
                """,
                MapUtils.of(
                        "errorf", GoStdlibTypes.Fmt.Errorf
                ));
    }

    private GoWriter.Writable generateContextFuncs() {
        return goTemplate("""
                type identityKey struct{}

                func setIdentity(ctx $context:T, identity $identity:T) $context:T {
                    return $withStackValue:T(ctx, identityKey{}, identity)
                }

                func getIdentity(ctx $context:T) $identity:T {
                    v, _ := $getStackValue:T(ctx, identityKey{}).($identity:T)
                    return v
                }
                """,
                MapUtils.of(
                        "context", GoStdlibTypes.Context.Context,
                        "withStackValue", SmithyGoTypes.Middleware.WithStackValue,
                        "getStackValue", SmithyGoTypes.Middleware.GetStackValue,
                        "identity", SmithyGoTypes.Auth.Identity
                ));
    }
}
