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

package software.amazon.smithy.go.codegen.protocol.aws;

import static software.amazon.smithy.go.codegen.ApplicationProtocol.createDefaultHttpApplicationProtocol;

import software.amazon.smithy.aws.traits.protocols.AwsJson1_0Trait;
import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
//import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.model.shapes.ShapeId;
//import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public class AwsJson10ProtocolGenerator implements ProtocolGenerator {
    @Override
    public ShapeId getProtocol() {
        return AwsJson1_0Trait.ID;
    }

    @Override
    public ApplicationProtocol getApplicationProtocol() {
        return createDefaultHttpApplicationProtocol();
    }

    @Override
    public void generateRequestSerializers(GenerationContext context) {
        var writer = context.getWriter().get();
        var model = context.getModel();
        var ops = model.getOperationShapes();
        for (var op : ops) {
            var opName = op.toShapeId().getName();

            //Struct Definition
            var structName = "awsAwsjson10_serializeOp" + opName;
            writer.write("type " + structName + " struct{\n}\n");

            //ID Function
//            var opID = opName + "Serializer";
// an id for the operation's serializer, that includes the specific op name
            var opID = "OperationSerializer";
            var idFunction = "func (op *$L) ID() string {\n return \"$L\" \n}\n";
            writer.write(idFunction, structName, opID);

            //Handle Serialize Function
            var handleFunction = """
                    func (op *$L) HandleSerialize (
                    ctx $T, in $T, next $T,
                    )(out $T, metadata $T, err error) {
                        return next.HandleSerialize(ctx, in)
                    }
                    \n""";
            writer.write(handleFunction, structName, SmithyGoDependency.CONTEXT.interfaceSymbol("Context"),
                    SmithyGoDependency.SMITHY_MIDDLEWARE.struct("SerializeInput"),
                    SmithyGoDependency.SMITHY_MIDDLEWARE.struct("SerializeHandler"),
                    SmithyGoDependency.SMITHY_MIDDLEWARE.struct("SerializeOutput"),
                    SmithyGoDependency.SMITHY_MIDDLEWARE.struct("Metadata"));

            //Code for if ID were to be contained in
//            writer.write("\"ID\": $L", opID); //body of struct
//            writer.write("}"); //close body of struct

            //Ending Operation
            writer.write("\n");
        }
    }

    @Override
    public void generateResponseDeserializers(GenerationContext context) {
        context.getWriter().get().write("// TODO");
    }

    @Override
    public void generateProtocolDocumentMarshalerMarshalDocument(GenerationContext context) {
        // TODO
    }

    @Override
    public void generateProtocolDocumentMarshalerUnmarshalDocument(GenerationContext context) {
        // TODO
    }

    @Override
    public void generateProtocolDocumentUnmarshalerMarshalDocument(GenerationContext context) {
        // TODO
    }

    @Override
    public void generateProtocolDocumentUnmarshalerUnmarshalDocument(GenerationContext context) {
        // TODO
    }
}
