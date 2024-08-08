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
import static software.amazon.smithy.go.codegen.server.protocol.JsonSerializerGenerator.getSerializerName;

import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.utils.MapUtils;

public class SerializeMiddleware {
    protected final ProtocolGenerator.GenerationContext ctx;
    protected final OperationShape operation;
    protected final GoWriter writer;

    protected final StructureShape input;
    protected final StructureShape output;

    private String serialName;
    public static final String CONTENT_TYPE = "application/x-amz-json-1.0";

    public SerializeMiddleware(
            ProtocolGenerator.GenerationContext ctx, OperationShape operation, GoWriter writer
    ) {
        this.ctx = ctx;
        this.operation = operation;
        this.writer = writer;

        this.input = ctx.getModel().expectShape(operation.getInputShape(), StructureShape.class);
        this.output = ctx.getModel().expectShape(operation.getOutputShape(), StructureShape.class);
    }

    public static String getSerializerName(OperationShape operation) {
        return "awsAwsjson10_serializeOp" + operation.toShapeId().getName();
    }

    public GoWriter.Writable generate() {
        serialName = getSerializerName(operation);

        return goTemplate("""

            type $opName:L struct{
            }

            func (op *$opName:L) ID() string {
                return "OperationSerializer"
            }

            $handleSerialize:W

            """,
            MapUtils.of(
                "opName", serialName,
                "handleSerialize", generateHandleSerialize()
        ));
    }

    private GoWriter.Writable generateHandleSerialize() {
        return goTemplate(
                """

                        func (op *$opName:L) HandleSerialize (ctx $context:T, in $input:T, next $handler:T) (
                        out $output:T, metadata $metadata:T, err error) {

                            $body:W

                            return next.HandleSerialize(ctx, in)
                        }

                        """, MapUtils.of(
                        "context", SmithyGoDependency.CONTEXT.interfaceSymbol("Context"),
                        "input", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("SerializeInput"),
                        "handler", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("SerializeHandler"),
                        "output", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("SerializeOutput"),
                        "metadata", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("Metadata"),
                        "opName", serialName,
                        "body", generateHandleSerializeBody())
        );
    }

    private GoWriter.Writable generateHandleSerializeBody() {
        return goTemplate("""
                $route:W

                $serialize:W

            """,
            MapUtils.of(
                    "route", handleProtocolSetup(),
                    "serialize", handlePayload()
            ));
    }

    private GoWriter.Writable handleProtocolSetup() {

        return goTemplate("""
                input, ok := in.Parameters.($input:P)
                if !ok {
                    return out, metadata, $errorf:T("unexpected input type %T", in.Parameters)
                }
                _ = input

                req, ok := in.Request.($request:P)
                if !ok {
                    return out, metadata, $errorf:T("unexpected transport type %T", in.Request)
                }

                req.Method = $methodPost:T
                req.URL.Path = "/"
                req.Header.Set("Content-Type", $contentType:S)
                req.Header.Set("X-Amz-Target", $target:S)

            """,
            MapUtils.of(
                "input", ctx.getSymbolProvider().toSymbol(input),
                "request", SMITHY_HTTP_TRANSPORT.pointableSymbol("Request"),
                "methodPost", GoStdlibTypes.Net.Http.MethodPost,
                "contentType", CONTENT_TYPE,
                "target", ctx.getService().getId().getName() + '.' + operation.getId().getName(),
                "errorf", GoStdlibTypes.Fmt.Errorf
                ));
    }

    private GoWriter.Writable handlePayload() {
        return goTemplate("""

                jsonEncoder := $encoder:T()

                err = $serialize:L(input, jsonEncoder.Value)
                if err != nil {
                    return out, metadata, &$error:T{Err: err}
                }

                payload := $reader:T(jsonEncoder.Bytes())
                req, err = req.SetStream(payload)
                if err != nil {
                    return out, metadata, &$error:T{Err: err}
                }

                in.Request = req
            """,
            MapUtils.of(
                "serialize", getSerializerName(input),
                "encoder", SmithyGoTypes.Encoding.Json.NewEncoder,
                "reader", GoStdlibTypes.Bytes.NewReader,
                "error", SmithyGoTypes.Smithy.SerializationError
        ));
    }
}
