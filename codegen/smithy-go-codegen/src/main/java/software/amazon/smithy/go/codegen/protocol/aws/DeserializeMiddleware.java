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

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SmithyGoDependency.SMITHY_HTTP_TRANSPORT;
import static software.amazon.smithy.go.codegen.integration.ProtocolGenerator.getOperationErrorDeserFunctionName;
import static software.amazon.smithy.go.codegen.protocol.ProtocolUtil.hasEventStream;
import static software.amazon.smithy.go.codegen.server.protocol.JsonDeserializerGenerator.getDeserializerName;

import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.utils.MapUtils;

public class DeserializeMiddleware {

    protected final ProtocolGenerator.GenerationContext ctx;
    protected final OperationShape operation;
    protected final GoWriter writer;

    protected final StructureShape input;
    protected final StructureShape output;

    private String deserialName;

    public DeserializeMiddleware(
            ProtocolGenerator.GenerationContext ctx, OperationShape operation, GoWriter writer
    ) {
        this.ctx = ctx;
        this.operation = operation;
        this.writer = writer;

        this.input = ctx.getModel().expectShape(operation.getInputShape(), StructureShape.class);
        this.output = ctx.getModel().expectShape(operation.getOutputShape(), StructureShape.class);

        deserialName = getMiddlewareName(operation);
    }

    public static String getMiddlewareName(OperationShape operation) {
        return "awsAwsjson10_deserializeOp" + operation.toShapeId().getName();
    }

    public GoWriter.Writable generate() {
        return goTemplate("""

            type $opName:L struct{
            }

            func (op *$opName:L) ID() string {
                return "OperationDeserializer"
            }

            $handleSerialize:W

            """,
                MapUtils.of(
                        "opName", deserialName,
                        "handleSerialize", generateHandleDeserialize()
                ));
    }

    private GoWriter.Writable generateHandleDeserialize() {
        return goTemplate(
                """

                        func (op *$opName:L) HandleDeserialize (ctx $context:T, in $input:T, next $handler:T) (
                        out $output:T, metadata $metadata:T, err error) {

                            $body:W

                            return out, metadata, nil
                        }

                        """, MapUtils.of(
                        "context", SmithyGoDependency.CONTEXT.interfaceSymbol("Context"),
                        "input", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("DeserializeInput"),
                        "handler", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("DeserializeHandler"),
                        "output", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("DeserializeOutput"),
                        "metadata", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("Metadata"),
                        "opName", deserialName,
                        "body", generateHandleDeserializeBody())
        );
    }

    private GoWriter.Writable generateHandleDeserializeBody() {
        return goTemplate("""
                $errors:W

                $response:W
            """,
                MapUtils.of(
                        "errors", handleResponseChecks(),
                        "response", handleResponse()
                ));
    }


    private GoWriter.Writable handleResponseChecks() {
        return goTemplate("""

                out, metadata, err = next.HandleDeserialize(ctx, in)
                if err != nil {
                    return out, metadata, err
                }

                resp, ok := out.RawResponse.($response:P)
                if !ok {
                    return out, metadata, $errorf:T("unexpected transport type %T", out.RawResponse)
                }

                if resp.StatusCode < 200 || resp.StatusCode >= 300 {
                    return out, metadata, $errorDeserialized:L(resp)
                }

            """,
                MapUtils.of(
                        "response", SMITHY_HTTP_TRANSPORT.pointableSymbol("Response"),
                        "errorf", GoStdlibTypes.Fmt.Errorf,
                        "errorDeserialized", getOperationErrorDeserFunctionName(operation, ctx.getService(),
                                "awsJson10")
                ));
    }

    private GoWriter.Writable handleResponse() {
        if (output.members().isEmpty()) {
            return discardDeserialize();
        } else if (hasEventStream(ctx.getModel(), output)) {
            return deserializeEventStream();
        }
        return handlePayload();
    }

    private GoWriter.Writable handlePayload() {
        return goTemplate("""
                payload, err := $readAll:T(resp.Body)
                if err != nil {
                    return out, metadata, err
                }

                if len(payload) == 0 {
                    out.Result = &$output:T{}
                    return out, metadata, nil
                }

                decoder := $decoder:T(resp.Body)
                var jv map[string]interface{}
                err = decoder.Decode(&jv)
                if err!= nil {
                    return out, metadata, err
                }

                output, err := $deserialize:L(jv)
                if err != nil {
                    return out, metadata, err
                }

                    out.Result = output
                """,
                MapUtils.of(
                        "readAll", GoStdlibTypes.Io.ReadAll,
                        "output", ctx.getSymbolProvider()
                                .toSymbol(ctx.getModel().expectShape(operation.getOutputShape())),
                        "decoder", GoStdlibTypes.Encoding.Json.NewDecoder,
                        "deserialize", getDeserializerName(output)
                ));
    }

    private GoWriter.Writable discardDeserialize() {
        return goTemplate("""
                if _, err = $copy:T($discard:T, resp.Body); err != nil {
                    return out, metadata, $errorf:T("discard response body: %w", err)
                }

                out.Result = &$result:T{}
                """,
                MapUtils.of(
                        "copy", GoStdlibTypes.Io.Copy,
                        "discard", GoStdlibTypes.Io.IoUtil.Discard,
                        "errorf", GoStdlibTypes.Fmt.Errorf,
                        "result", ctx.getSymbolProvider().toSymbol(output)
                ));
    }

    // Basically a no-op. Event stream deserializer middleware, implemented elsewhere, will handle the wire-up here,
    // including handling the initial-response message to deserialize any non-stream members to output.
    // Taken straight from CBOR implementation
    private GoWriter.Writable deserializeEventStream() {
        return goTemplate("out.Result = &$T{}", ctx.getSymbolProvider().toSymbol(output));
    }
}


