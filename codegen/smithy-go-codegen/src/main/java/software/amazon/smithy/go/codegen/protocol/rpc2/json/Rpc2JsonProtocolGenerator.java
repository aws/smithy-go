package software.amazon.smithy.go.codegen.protocol.rpc2.json;

import static software.amazon.smithy.go.codegen.ApplicationProtocol.createDefaultHttpApplicationProtocol;

import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.protocol.traits.Rpcv2JsonTrait;
import software.amazon.smithy.utils.SmithyInternalApi;

// this is a stub, we only need it because right now the client codegen plugin
// has to see a ProtocolGenerator in order for it to continue, even if it's
// doing schema-serde
//
// when we finish the schema-serde rollout in sdkv2 we can nuke all this
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
