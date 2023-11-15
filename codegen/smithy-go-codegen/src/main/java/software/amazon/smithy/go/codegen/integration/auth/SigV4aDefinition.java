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

package software.amazon.smithy.go.codegen.integration.auth;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.integration.AuthSchemeDefinition;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;

/**
 * Implements codegen for aws.auth#sigv4a.
 */
public class SigV4aDefinition implements AuthSchemeDefinition {
    // FUTURE: reference modeled sigv4a trait

    @Override
    public GoWriter.Writable generateServiceOption(
            ProtocolGenerator.GenerationContext context, ServiceShape service
    ) {
        return goTemplate("&$T{SchemeID: $T},",
                SmithyGoTypes.Auth.Option,
                SmithyGoTypes.Auth.SchemeIDSigV4A);
    }

    @Override
    public GoWriter.Writable generateOperationOption(
            ProtocolGenerator.GenerationContext context, OperationShape operation
    ) {
        return goTemplate("&$T{SchemeID: $T},",
                SmithyGoTypes.Auth.Option,
                SmithyGoTypes.Auth.SchemeIDSigV4A);
    }
}
