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
import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.MiddlewareIdentifier;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.utils.MapUtils;

public class SignRequestMiddlewareGenerator {
    public static final String MIDDLEWARE_NAME = "signRequestMiddleware";
    public static final String MIDDLEWARE_ID = "Signing";

    private final ProtocolGenerator.GenerationContext context;

    public SignRequestMiddlewareGenerator(ProtocolGenerator.GenerationContext context) {
        this.context = context;
    }

    public static GoWriter.Writable generateAddMiddleware() {
        return goTemplate("""
                err = stack.Finalize.Add(&$L{}, $T)
                if err != nil {
                    return err
                }
                """, MIDDLEWARE_NAME, SmithyGoTypes.Middleware.Before);
    }

    public GoWriter.Writable generate() {
        return createFinalizeStepMiddleware(MIDDLEWARE_NAME, MiddlewareIdentifier.string(MIDDLEWARE_ID))
                .asWritable(generateBody(), generateFields());
    }

    private GoWriter.Writable generateFields() {
        return emptyGoTemplate();
    }

    private GoWriter.Writable generateBody() {
        return goTemplate("""
                req, ok := in.Request.($request:P)
                if !ok {
                    return out, metadata, $errorf:T("unexpected transport type %T", in.Request)
                }

                rscheme := getResolvedAuthScheme(ctx)
                if rscheme == nil {
                    return out, metadata, $errorf:T("no resolved auth scheme")
                }

                identity := getIdentity(ctx)
                if identity == nil {
                    return out, metadata, $errorf:T("no identity")
                }

                signer := rscheme.Scheme.Signer()
                if signer == nil {
                    return out, metadata, $errorf:T("no signer")
                }

                if err := signer.SignRequest(ctx, req, identity, rscheme.SignerProperties); err != nil {
                    return out, metadata, $errorf:T("sign request: %v", err)
                }

                return next.HandleFinalize(ctx, in)
                """,
                MapUtils.of(
                        // FUTURE(#458) protocol generator should specify the transport type
                        "request", SmithyGoTypes.Transport.Http.Request,
                        "errorf", GoStdlibTypes.Fmt.Errorf
                ));
    }
}
