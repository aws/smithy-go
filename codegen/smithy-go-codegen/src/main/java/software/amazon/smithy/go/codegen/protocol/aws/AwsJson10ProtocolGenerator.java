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
import software.amazon.smithy.go.cogitdegen.GoWriter;
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
        var writer = context.getWriter().get();
        var model = context.getModel();
        var ops = model.getOperationShapes();
        for (var op : ops) {
            responseDeserializerCode(op, writer);
        }
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
        var opName = "awsAwsjson10_serializeOp" + op.toShapeId().getName();;

        /*Struct Definition*/
        var struct = goTemplate("""
                type $L struct{
                }
                """, opName
        );
        writer.write(struct);


        /* ID Function */
        var idFunction = goTemplate("""
                func (op *$L) ID() string {
                    return "OperationSerializer"
                }
                """, opName
        );
        writer.write(idFunction);

        /* Handle Serialize Function */
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
                        "structName", opName
                ));
        writer.write(handleFunction);

        /* Operation End */
        writer.write("\n");
    }

    private void responseDeserializerCode(OperationShape op, GoWriter writer) {
        var opName = "awsAwsjson10_deserializeOp" + op.toShapeId().getName();

        /* Struct Definition */
        var struct = goTemplate("""
                type $L struct{
                }
                """, opName
        );
        writer.write(struct);


        //ID Function
        var idFunction = goTemplate("""
                func (op *$L) ID() string {
                    return "OperationDeserializer"
                }
                """, opName
        );
        writer.write(idFunction);

        //Handle Serialize Function
        var handleFunction = goTemplate(
                """
                func (op *$structName:L) HandleDeserialize (ctx $context:T, in $input:T, next $handler:T) (
                out $output:T, metadata $metadata:T, err error) {
                    return out, metadata, err
                }
                """, Map.of(
                        "context", SmithyGoDependency.CONTEXT.interfaceSymbol("Context"),
                        "input", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("DeserializeInput"),
                        "handler", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("DeserializeHandler"),
                        "output", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("DeserializeOutput"),
                        "metadata", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("Metadata"),
                        "structName", opName
                ));
        writer.write(handleFunction);
        /* Operation End */
        writer.write("\n");
    }
}
