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

package software.amazon.smithy.go.codegen.auth;

import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoWriter;

public class AuthGenerator {
    private final GoCodegenContext ctx;
    private final GoWriter writer;

    public AuthGenerator(GoCodegenContext ctx, GoWriter writer) {
        this.ctx = ctx;
        this.writer = writer;
    }

    public void generate() {
        writer
                .write("$W\n", new AuthParametersGenerator(ctx).generate())
                .write("$W\n", new AuthParametersResolverGenerator(ctx).generate())
                .write("$W\n", new AuthSchemeResolverGenerator(ctx).generate())
                .write("$W\n", new ResolveAuthSchemeMiddlewareGenerator().generate())
                .write("$W\n", new GetIdentityMiddlewareGenerator().generate())
                .write("$W\n", new SignRequestMiddlewareGenerator().generate());
    }
}
