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

package software.amazon.smithy.go.codegen.provider;

import software.amazon.smithy.go.codegen.GoDependency;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.integration.GoIntegration;


/**
 * Generates Value provider for Idempotency Token middleware.
 *
 */
public class IdempotencyTokenValueProvider implements GoIntegration {
    @Override
    public void generateValueForIdempotencyToken(
        GoWriter writer,
        String operand
    ) {
        writer.addUseImports(GoDependency.SMITHY_RAND);
        writer.addUseImports(GoDependency.CRYPTORAND);
        writer.write("uuid := smithyrand.NewUUID(cryptorand.Reader)");
        writer.write("$L, err := uuid.GetUUID()", operand);
        writer.write("if err != nil { return out, metadata, err}");
    }
}
