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

import static software.amazon.smithy.go.codegen.GoStackStepMiddlewareGenerator.createSerializeStepMiddleware;
import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.server.protocol.JsonSerializerGenerator.getSerializerName;

import java.util.Map;
import software.amazon.smithy.go.codegen.EventStreamGenerator;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.go.codegen.integration.ProtocolUtils;
import software.amazon.smithy.go.codegen.trait.BackfilledInputOutputTrait;
import software.amazon.smithy.model.knowledge.EventStreamIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.utils.MapUtils;





@SuppressWarnings("checkstyle:RegexpSingleline")
public class SerializeMiddleware implements GoWriter.Writable {
    protected final ProtocolGenerator generator;
    protected final ProtocolGenerator.GenerationContext ctx;
    protected final OperationShape operation;
    protected final GoWriter writer;

    protected final StructureShape input;
    protected final StructureShape output;
    private final EventStreamIndex eventStreamIndex;

//    private final String SMITHY_PROTOCOL_NAME = "awsjson10";
    private final String contentType = "application/awsjson10";
    private String serialName;

    public SerializeMiddleware(
            ProtocolGenerator generator, ProtocolGenerator.GenerationContext ctx, OperationShape operation,
            GoWriter writer
    ) {
        this.generator = generator;
        this.ctx = ctx;
        this.operation = operation;
        this.writer = writer;

        this.input = ctx.getModel().expectShape(operation.getInputShape(), StructureShape.class);
        this.output = ctx.getModel().expectShape(operation.getOutputShape(), StructureShape.class);
        this.eventStreamIndex = EventStreamIndex.of(ctx.getModel());
    }

    @Override
    public void accept(GoWriter writer) {
        var name = ProtocolGenerator.getSerializeMiddlewareName(operation.getId(), ctx.getService(),
                generator.getProtocolName());
        var middleware = createSerializeStepMiddleware(name, ProtocolUtils.OPERATION_SERIALIZER_MIDDLEWARE_ID);
//        writer.write(middleware.asWritable(generateOperationStubs(), emptyGoTemplate()));
    }

    public GoWriter.Writable generateOperationStubs() {
        this.serialName = "awsAwsjson10_serializeOp" + this.operation.toShapeId().getName();

        return goTemplate("""
                
                type $opName:L struct{
                }
                
                func (op *$opName:L) ID() string {
                    return "OperationSerializer"
                }
                
                $handleSerialize:W
                
                """, Map.of(
                    "opName", this.serialName,
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
                        
                        """, Map.of(
                        "context", SmithyGoDependency.CONTEXT.interfaceSymbol("Context"),
                        "input", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("SerializeInput"),
                        "handler", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("SerializeHandler"),
                        "output", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("SerializeOutput"),
                        "metadata", SmithyGoDependency.SMITHY_MIDDLEWARE.struct("Metadata"),
                        "opName", this.serialName,
                        "body", generateHandleSerializeBody())
        );
    }

    private GoWriter.Writable generateHandleSerializeBody() {
        return goTemplate("""
                    $route:W
    
                    $serialize:W
    
                """,
                MapUtils.of(
                        "input", ctx.getSymbolProvider().toSymbol(input),
                        "request", generator.getApplicationProtocol().getRequestType(),
                        "route", handleHttp(),
                        "serialize", handlePayload(),
                        "errorf", GoStdlibTypes.Fmt.Errorf
                ));
    }

    private GoWriter.Writable handleHttp() {
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
                    req.URL.Path = "/service/$service:L/operation/$operation:L"
                    req.Header.Set("smithy-protocol", $protocol:S)
                
                    $contentTypeHeader:W
                    $acceptHeader:W
                
                """,
        MapUtils.of(
                "input", ctx.getSymbolProvider().toSymbol(input),
                "request", generator.getApplicationProtocol().getRequestType(),
                "methodPost", GoStdlibTypes.Net.Http.MethodPost,
                "service", ctx.getService().getId().getName(),
                "operation", operation.getId().getName(),
                "protocol", generator.getProtocol(),
                "contentTypeHeader", setContentTypeHeader(),
                "acceptHeader", acceptHeader(),
                "serialize", handlePayload(),
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
                if req, err = req.SetStream(payload); err != nil {
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

    private GoWriter.Writable setContentTypeHeader() {
        if (input.hasTrait(BackfilledInputOutputTrait.class)) {
            return emptyGoTemplate();
        }

        return goTemplate("""
                req.Header.Set("Content-Type", $S)
                """, isInputEventStream() ? EventStreamGenerator.AMZ_CONTENT_TYPE : contentType);
    }

    private GoWriter.Writable acceptHeader() {
        return goTemplate("""
                req.Header.Set("Accept", $S)
                """, isOutputEventStream() ? EventStreamGenerator.AMZ_CONTENT_TYPE : contentType);
    }

    private boolean isInputEventStream() {
        return eventStreamIndex.getInputInfo(operation).isPresent();
    }

    private boolean isOutputEventStream() {
        return eventStreamIndex.getOutputInfo(operation).isPresent();
    }
}
