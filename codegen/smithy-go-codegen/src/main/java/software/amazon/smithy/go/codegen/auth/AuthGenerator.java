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

import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;

/**
 * Entry point into smithy client auth generation.
 */
public class AuthGenerator {
    private final ProtocolGenerator.GenerationContext context;

    public AuthGenerator(ProtocolGenerator.GenerationContext context) {
        this.context = context;
    }

    public void generate() {
        if (context.getWriter().isEmpty()) {
            throw new CodegenException("writer is required");
        }

        context.getWriter().get()
                .write("$W", new AuthParametersGenerator(context).generate())
                .write("")
                .write("$W", new AuthParametersResolverGenerator(context).generate())
                .write("")
                .write("$W", getResolverGenerator().generate());
    }

    // TODO(i&a): allow consuming generators to overwrite
    private AuthSchemeResolverGenerator getResolverGenerator() {
        return new AuthSchemeResolverGenerator(context);
    }
}
