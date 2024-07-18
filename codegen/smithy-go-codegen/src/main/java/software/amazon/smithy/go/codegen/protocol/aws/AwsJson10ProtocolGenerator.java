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
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.Map;
import software.amazon.smithy.aws.traits.protocols.AwsJson1_0Trait;
import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
//import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ShapeId;
//import software.amazon.smithy.model.shapes.StructureShape;
//import software.amazon.smithy.utils.MapUtils;
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
            requestSerializerCode(op, writer);
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

    private void requestSerializerCode(OperationShape op, GoWriter writer) {
        var opName = op.toShapeId().getName();
        var structName = "awsAwsjson10_serializeOp" + opName;

        //Struct Definition
        var struct = goTemplate("""
                type $L struct{
                }
                """, structName
        );
        writer.write(struct);


        //ID Function
        var idFunction = goTemplate("""
                func (op *$L) ID() string {
                    return "OperationSerializer"
                    }
                """, structName
        );
        writer.write(idFunction);

        //Handle Serialize Function
        var handleFunction = goTemplate(
                    """
                    func (op *$structName:L) HandleSerialize (ctx $context:T, in $input:T, next $handler:T) (
                    out $output:T, metadata $metadata:T, err error) {
                        return next.HandleSerialize(ctx, in)
                    }
                    """, Map.of(
                            "context", SmithyGoDependency.CONTEXT.interfaceSymbol("Context"),
                        "input", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("SerializeInput"),
                        "handler", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("SerializeHandler"),
                        "output", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("SerializeOutput"),
                        "metadata", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("Metadata"),
                        "structName", structName
                ));
        writer.write(handleFunction);
        //Code for if ID were to be contained in
//            writer.write("\"ID\": $L", opID); //body of struct
//            writer.write("}"); //close body of struct

        //Ending Operation
        writer.write("\n");
    }
}
