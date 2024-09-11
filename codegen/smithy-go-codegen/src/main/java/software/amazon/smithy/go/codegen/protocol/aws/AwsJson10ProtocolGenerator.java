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
import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.protocol.ProtocolUtil.GET_AWS_QUERY_ERROR_CODE;
import static software.amazon.smithy.go.codegen.serde.SerdeUtil.getShapesToSerde;
import static software.amazon.smithy.go.codegen.server.protocol.JsonDeserializerGenerator.getDeserializerName;

import java.util.HashSet;
import java.util.Set;
import software.amazon.smithy.aws.traits.protocols.AwsJson1_0Trait;
import software.amazon.smithy.aws.traits.protocols.AwsQueryCompatibleTrait;
import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.go.codegen.server.protocol.JsonDeserializerGenerator;
import software.amazon.smithy.go.codegen.server.protocol.JsonSerializerGenerator;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.utils.MapUtils;
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
        var ops = context.getModel().getOperationShapes();
        for (var op : ops) {
            var middle = new SerializeMiddleware(context, op, writer);
            writer.write(middle.generate());
            writer.write("\n");
        }
        generateSharedSerializers(context, writer, ops);
    }

    public void generateSharedSerializers(GenerationContext context, GoWriter writer, Set<OperationShape> ops) {
        Set<Shape> shared = new HashSet<>();
        for (var op : ops) {
            Set<Shape> shapes = getShapesToSerde(context.getModel(), context.getModel().expectShape(
                    op.getInputShape()));
            shared.addAll(shapes);
        }
        var generator = new JsonSerializerGenerator(context.getModel(), context.getSymbolProvider());
        writer.write(generator.generate(shared));
    }

    @Override
    public void generateResponseDeserializers(GenerationContext context) {
        var writer = context.getWriter().get();
        var ops = context.getModel().getOperationShapes();
        for (var op : ops) {
            var middle = new DeserializeMiddleware(context, op, writer);
            writer.write(middle.generate());
            writer.write("\n");
        }
        generateSharedDeserializers(context, writer, ops);
        generateErrorDeserializers(context, ops);
    }

    private void generateSharedDeserializers(GenerationContext context, GoWriter writer, Set<OperationShape> ops) {
        Set<Shape> shared = new HashSet<>();
        for (var op : ops) {
            Set<Shape> shapes = getShapesToSerde(context.getModel(), context.getModel().expectShape(
                    op.getOutputShape()));
            shared.addAll(shapes);
        }
        var errorShapes = generateErrorDeserializers(context, ops);
        shared.addAll(errorShapes);

        var generator = new JsonDeserializerGenerator(context.getModel(), context.getSymbolProvider());
        writer.write(generator.generate(shared));

        generateOperationErrorDeserializers(context, writer, ops);

        writer.write(getProtocolErrorInfo());

        if (context.getService().hasTrait(AwsQueryCompatibleTrait.class)) {
            writer.write(GET_AWS_QUERY_ERROR_CODE);
        }
    }

    private Set<Shape> generateErrorDeserializers(GenerationContext context, Set<OperationShape> ops) {
        Set<Shape> errorShapes = new HashSet<>();
        for (var op : ops) {
            var errors = op.getErrors();
            for (var error : errors) {
                Set<Shape> shapes = getShapesToSerde(context.getModel(), context.getModel().expectShape(error));
                errorShapes.addAll(shapes);
            }
        }
        return errorShapes;
    }

    private void generateOperationErrorDeserializers(
            GenerationContext context, GoWriter writer, Set<OperationShape> operations) {
        for (var operation : operations) {
            var errors = context.getService().getErrors()
                    .stream()
                    .map(it -> deserializeErrorCase(context, context.getModel().expectShape(it, StructureShape.class)))
                    .toList();
            writer.write(goTemplate("""
                func $func:L(resp $smithyhttpResponse:P) error {
                    payload, err := $readAll:T(resp.Body)
                    if err != nil {
                        return &$deserError:T{Err: $fmtErrorf:T("read response body: %w", err)}
                    }

                    typ, msg, v, err := getProtocolErrorInfo(payload)
                    if err != nil {
                        return &$deserError:T{Err: $fmtErrorf:T("get error info: %w", err)}
                    }

                    if len(typ) == 0 {
                        typ = "UnknownError"
                    }
                    if len(msg) == 0 {
                        msg = "UnknownError"
                    }

                    _ = v
                    switch typ {
                    $errors:W
                    default:
                        $awsQueryCompatible:W
                        return &$genericAPIError:T{Code: typ, Message: msg}
                    }
                }
                """,
                    MapUtils.of(
                            "deserError", SmithyGoDependency.SMITHY.pointableSymbol("DeserializationError"),
                            "fmtErrorf", GoStdlibTypes.Fmt.Errorf,
                            "func", ProtocolGenerator.getOperationErrorDeserFunctionName(operation,
                                    context.getService(), "awsJson10"),
                            "genericAPIError", SmithyGoDependency.SMITHY.pointableSymbol("GenericAPIError"),
                            "readAll", SmithyGoDependency.IO.func("ReadAll"),
                            "smithyhttpResponse", SmithyGoTypes.Transport.Http.Response,
                            "awsQueryCompatible", context.getService().hasTrait(AwsQueryCompatibleTrait.class)
                                    ? deserializeAwsQueryError()
                                    : emptyGoTemplate(),
                            "errors", GoWriter.ChainWritable.of(errors).compose(false)
                    )));
        }
    }

    private GoWriter.Writable deserializeErrorCase(GenerationContext ctx, StructureShape error) {
        return goTemplate("""
                case $type:S:
                    verr, err := $deserialize:L(v)
                    if err != nil {
                        return &$deserError:T{
                            Err: $fmtErrorf:T("deserialize $type:L: %w", err),
                            Snapshot: payload,
                        }
                    }
                    $awsQueryCompatible:W
                    return verr
                """,
                MapUtils.of(
                        "deserError", SmithyGoDependency.SMITHY.pointableSymbol("DeserializationError"),
                        "deserialize", getDeserializerName(error),
                        "equalFold", SmithyGoDependency.STRINGS.func("EqualFold"),
                        "fmtErrorf", GoStdlibTypes.Fmt.Errorf,
                        "type", error.getId().toString(),
                        "awsQueryCompatible", ctx.getService().hasTrait(AwsQueryCompatibleTrait.class)
                                ? deserializeModeledAwsQueryError()
                                : emptyGoTemplate()
                ));
    }

    private GoWriter.Writable deserializeAwsQueryError() {
        return goTemplate("""
                if qtype := getAwsQueryErrorCode(resp); len(qt) > 0 {
                    typ = qtype
                }""");
    }

    private GoWriter.Writable deserializeModeledAwsQueryError() {
        return goTemplate("""
                if qtype := getAwsQueryErrorCode(resp); len(qt) > 0 {
                    verr.ErrorCodeOverride = $T(qtype)
                }""", SmithyGoTypes.Ptr.String);
    }

    private GoWriter.Writable getProtocolErrorInfo() {
        return goTemplate("""
            func getProtocolErrorInfo(payload []byte) (typ, msg string, v $value:T, err error) {

                paid := $reader:T(payload)
                jsonDecoder := $decoder:T(paid)
                var val interface{}
                var jv map[string]interface{}

                jsonDecoder.Decode(&val)
                if err != nil {
                    return "", "", val.($value:T), $fmtErrorf:T("decode: %w", err)
                }

                err = jsonDecoder.Decode(&jv)
                if err != nil {
                    return "", "", val.($value:T), $fmtErrorf:T("decode: %w", err)
                }

                if jtyp, ok := jv["__type"]; ok {
                    typ = jtyp.(string)
                } else if jtyp, ok = jv["code"]; ok {
                    typ = jtyp.(string)
                }
                // TODO: Add in Header Check for "x-amzn-errortype"

                typ = sanitizeProtocolErrorTyp(typ)

                if jmsg, ok := jv["message"]; ok {
                    msg = jmsg.(string)
                }

                return typ, msg, val.($value:T), nil
            }

            func sanitizeProtocolErrorTyp(typ string) (val string){
                val = typ
                hashIndex, colonIndex := 0, len(val)-1
                for idx := 0; idx < len(val); idx++ {
                    if val[idx] == '#' && hashIndex == 0 {
                        hashIndex = idx
                    }
                    if val[idx] == ':' && colonIndex == len(val)-1 {
                        colonIndex = idx
                    }
                }
                return val[hashIndex:colonIndex+1]
            }
            """,
            MapUtils.of(
                    "fmtErrorf", GoStdlibTypes.Fmt.Errorf,
                    "decoder", GoStdlibTypes.Encoding.Json.NewDecoder,
                    "value", SmithyGoTypes.Encoding.Json.Value,
                    "reader", GoStdlibTypes.Bytes.NewReader
            ));
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
