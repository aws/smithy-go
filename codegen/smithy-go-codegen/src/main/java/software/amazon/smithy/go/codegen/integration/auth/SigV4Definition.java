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

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.aws.traits.auth.SigV4Trait;
import software.amazon.smithy.aws.traits.auth.UnsignedPayloadTrait;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.integration.AuthSchemeDefinition;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;

/**
 * Implements codegen for aws.auth#sigv4.
 */
public class SigV4Definition implements AuthSchemeDefinition {
    @Override
    public GoWriter.Writable generateServiceOption(ProtocolGenerator.GenerationContext context, ServiceShape service) {
        var trait = service.expectTrait(SigV4Trait.class);
        return goTemplate("""
                $T(func (props $P) {
                    props.SigningName = $S
                    props.SigningRegion = params.Region
                }),""",
                SmithyGoTypes.Transport.Http.NewSigV4Option,
                SmithyGoTypes.Transport.Http.SigV4Properties,
                trait.getName());
    }

    @Override
    public GoWriter.Writable generateOperationOption(
            ProtocolGenerator.GenerationContext context,
            OperationShape operation
    ) {
        var trait = context.getService().expectTrait(SigV4Trait.class);
        return goTemplate("""
                $T(func (props $P) {
                    props.SigningName = $S
                    props.SigningRegion = params.Region
                    $W
                }),""",
                SmithyGoTypes.Transport.Http.NewSigV4Option,
                SmithyGoTypes.Transport.Http.SigV4Properties,
                trait.getName(),
                generateIsUnsignedPayload(operation));
    }

    private GoWriter.Writable generateIsUnsignedPayload(OperationShape operation) {
        return operation.hasTrait(UnsignedPayloadTrait.class)
                ? goTemplate("props.IsUnsignedPayload = true")
                : emptyGoTemplate();
    }
}
