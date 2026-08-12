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

package software.amazon.smithy.go.codegen.protocol.rpc2.json;

import static software.amazon.smithy.go.codegen.ApplicationProtocol.createDefaultHttpApplicationProtocol;

import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.protocol.traits.Rpcv2JsonTrait;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Declares support for smithy.protocols#rpcv2Json.
 *
 * <p>Serialization for this protocol is entirely schema-driven at runtime -- the
 * wire behavior lives in the {@code transport/http/protocol/rpcv2} runtime
 * package, selected by {@code ServiceGenerator}. Nothing protocol-specific needs
 * to be generated, so this type exists only so that protocol resolution can
 * settle on rpcv2Json, which in turn is what lets its protocol tests be
 * generated.
 *
 * <p>The legacy hand-written serde codegen path is not supported for this
 * protocol.
 */
@SmithyInternalApi
public final class Rpc2JsonProtocolGenerator implements ProtocolGenerator {
    @Override
    public ShapeId getProtocol() {
        return Rpcv2JsonTrait.ID;
    }

    @Override
    public ApplicationProtocol getApplicationProtocol() {
        return createDefaultHttpApplicationProtocol();
    }

    @Override
    public void generateRequestSerializers(GenerationContext ctx) {
        throw new CodegenException("rpcv2Json requires schema-based serde; "
                + "it cannot be generated with useLegacySerde");
    }

    @Override
    public void generateResponseDeserializers(GenerationContext ctx) {
        throw new CodegenException("rpcv2Json requires schema-based serde; "
                + "it cannot be generated with useLegacySerde");
    }
}
