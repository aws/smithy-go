/*
 * Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.go.codegen.service.protocol.aws;

import static java.util.stream.Collectors.toSet;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SymbolUtils.getReference;
import static software.amazon.smithy.go.codegen.SymbolUtils.isPointable;
import static software.amazon.smithy.go.codegen.service.Util.getShapesToSerde;
import static software.amazon.smithy.go.codegen.service.Util.normalize;

import software.amazon.smithy.aws.traits.protocols.AwsJson1_0Trait;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.service.NotImplementedError;
import software.amazon.smithy.go.codegen.service.ServerCodegenUtils;
import software.amazon.smithy.go.codegen.service.ServerInterface;
import software.amazon.smithy.go.codegen.service.protocol.HttpServerProtocolGenerator;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Implements the aws.protocols#awsJson1_0 protocol.
 */
@SmithyInternalApi
public final class AwsJson10ProtocolGenerator extends HttpServerProtocolGenerator {
    private final Model model;
    private final ServiceShape service;
    private final SymbolProvider symbolProvider;

    private final OperationIndex operationIndex;

    public AwsJson10ProtocolGenerator(Model model, ServiceShape service, SymbolProvider symbolProvider) {
        this.model = model;
        this.service = service;
        this.symbolProvider = symbolProvider;

        this.operationIndex = OperationIndex.of(model);
    }

    @Override
    public ShapeId getProtocol() {
        return AwsJson1_0Trait.ID;
    }


    @Override
    public GoWriter.Writable generateSource() {
        return GoWriter.ChainWritable.of(
                super.generateSource(),
                generateDeserializers(),
                //generateSerializers(),
                generateSerializeError()
        ).compose();
    }

    private String getDeserializerName(Shape shape) {
        return "awsJson10_deserialize" + shape.getId().getName();
    }

    private String getSerializerName(Shape shape) {
        return "awsJson10_serialize" + shape.getId().getName();
    }

    private GoWriter.Writable generateDeserializers() {
        return GoWriter.ChainWritable.of(
                TopDownIndex.of(model).getContainedOperations(service).stream()
                        .map(it -> model.expectShape(it.getInputShape(), StructureShape.class))
                        .flatMap(it -> getShapesToSerde(model, it).stream())
                        .collect(toSet()).stream()
                        .map(this::generateShapeDeserializer)
                        .toList()
        ).compose();
    }

    private GoWriter.Writable generateShapeDeserializer(Shape shape) {
        return goTemplate("""
                func $name:L(v interface{}) ($shapeType:P, error) {
                    av, ok := v.($assert:W)
                    if !ok {
                        return $zero:W, $error:T("invalid")
                    }
                    $deserialize:W
                }
                """,
                MapUtils.of(
                        "name", getDeserializerName(shape),
                        "shapeType", symbolProvider.toSymbol(shape),
                        "assert", generateOpaqueAssert(shape),
                        "zero", generateZeroValue(shape),
                        "error", GoStdlibTypes.Fmt.Errorf,
                        "deserialize", generateDeserializeAssertedValue(shape, "av")
                ));
    }

    private GoWriter.Writable generateOpaqueAssert(Shape shape) {
        return switch (shape.getType()) {
            case BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE, INT_ENUM ->
                    goTemplate("$T", GoStdlibTypes.Encoding.Json.Number);
            case STRING, BLOB, TIMESTAMP, ENUM, BIG_DECIMAL, BIG_INTEGER ->
                    goTemplate("string");
            case BOOLEAN ->
                    goTemplate("bool");
            case LIST, SET ->
                    goTemplate("[]interface{}");
            case MAP, STRUCTURE, UNION ->
                    goTemplate("map[string]interface{}");
            case DOCUMENT ->
                    throw new CodegenException("TODO: document is special");
            default ->
                    throw new CodegenException("? " + shape.getType());
        };
    }

    private GoWriter.Writable generateZeroValue(Shape shape) {
        return switch (shape.getType()) {
            case BYTE, SHORT, INTEGER, LONG, FLOAT, DOUBLE ->
                    goTemplate("0");
            case STRING ->
                    goTemplate("\"\"");
            case BOOLEAN ->
                    goTemplate("false");
            case BLOB, LIST, SET, MAP, STRUCTURE, UNION ->
                    goTemplate("nil");
            case ENUM ->
                    goTemplate("$T(\"\")", symbolProvider.toSymbol(shape));
            case INT_ENUM ->
                    goTemplate("$T(0)", symbolProvider.toSymbol(shape));
            case DOCUMENT ->
                    throw new CodegenException("TODO: document is special");
            default ->
                    throw new CodegenException("? " + shape.getType());
        };
    }

    private GoWriter.Writable generateDeserializeAssertedValue(Shape shape, String ident) {
        return switch (shape.getType()) {
            case BYTE -> generateDeserializeIntegral(ident, "int8", Byte.MIN_VALUE, Byte.MAX_VALUE);
            case SHORT -> generateDeserializeIntegral(ident, "int16", Short.MIN_VALUE, Short.MAX_VALUE);
            case INTEGER -> generateDeserializeIntegral(ident, "int32", Integer.MIN_VALUE, Integer.MAX_VALUE);
            case LONG -> generateDeserializeIntegral(ident, "int64", Long.MIN_VALUE, Long.MAX_VALUE);
            case STRING, BOOLEAN -> goTemplate("return $L, nil", ident);
            case ENUM -> goTemplate("return $T($L), nil", symbolProvider.toSymbol(shape), ident);
            case BLOB -> goTemplate("""
                    p, err := $b64:T.DecodeString($ident:L)
                    if err != nil {
                        return nil, err
                    }
                    return p, nil
                    """,
                    MapUtils.of(
                            "ident", ident,
                            "b64", GoStdlibTypes.Encoding.Base64.StdEncoding
                    ));
            case LIST, SET -> {
                var target = normalize(model.expectShape(((CollectionShape) shape).getMember().getTarget()));
                var symbol = symbolProvider.toSymbol(shape);
                var targetSymbol = symbolProvider.toSymbol(target);
                yield goTemplate("""
                        var deserializedList $type:T
                        for _, serializedItem := range $ident:L {
                            deserializedItem, err := $deserialize:L(serializedItem)
                            if err != nil {
                                return nil, err
                            }
                            deserializedList = append(deserializedList, $deref:L)
                        }
                        return deserializedList, nil
                        """,
                        MapUtils.of(
                                "type", symbol,
                                "ident", ident,
                                "deserialize", getDeserializerName(target),
                                "deref", isPointable(getReference(symbol)) != isPointable(targetSymbol)
                                        ? "*deserializedItem" : "deserializedItem"
                        ));
            }
            case MAP -> {
                var value = normalize(model.expectShape(((MapShape) shape).getValue().getTarget()));
                var symbol = symbolProvider.toSymbol(shape);
                var valueSymbol = symbolProvider.toSymbol(value);
                yield goTemplate("""
                        deserializedMap := $type:T{}
                        for key, serializedValue := range $ident:L {
                            deserializedValue, err := $deserialize:L(serializedValue)
                            if err != nil {
                                return nil, err
                            }
                            deserializedMap[key] = $deref:L
                        }
                        return deserializedMap, nil
                        """,
                        MapUtils.of(
                                "type", symbol,
                                "ident", ident,
                                "deserialize", getDeserializerName(value),
                                "deref", isPointable(getReference(symbol)) != isPointable(valueSymbol)
                                        ? "*deserializedValue" : "deserializedValue"
                        ));
            }
            case STRUCTURE -> goTemplate("""
                    deserializedStruct := &$type:T{}
                    for key, serializedValue := range $ident:L {
                        $deserializeFields:W
                    }
                    return deserializedStruct, nil
                    """,
                    MapUtils.of(
                            "type", symbolProvider.toSymbol(shape),
                            "ident", ident,
                            "deserializeFields", GoWriter.ChainWritable.of(
                                    shape.getAllMembers().entrySet().stream()
                                            .map(it -> {
                                                var target = model.expectShape(it.getValue().getTarget());
                                                return goTemplate("""
                                                        if key == $field:S {
                                                            fieldValue, err := $deserialize:L(serializedValue)
                                                            if err != nil {
                                                                return nil, err
                                                            }
                                                            deserializedStruct.$fieldName:L = $deref:W
                                                        }
                                                        """,
                                                        MapUtils.of(
                                                                "field", it.getKey(),
                                                                "fieldName", symbolProvider.toMemberName(it.getValue()),
                                                                "deserialize", getDeserializerName(normalize(target)),
                                                                "deref", generateStructFieldDeref(
                                                                        it.getValue(), "fieldValue")
                                                        ));
                                            })
                                            .toList()
                            ).compose(false)
                    ));
            case UNION -> goTemplate("// TODO (union)");
            default ->
                throw new CodegenException("? " + shape.getType());
        };
    }

    private GoWriter.Writable generateDeserializeIntegral(String ident, String castTo, long min, long max) {
        return goTemplate("""
                $nextident:L, err := $ident:L.Int64()
                if err != nil {
                    return 0, err
                }
                if $nextident:L < $min:L || $nextident:L > $max:L {
                    return 0, $errorf:T("invalid")
                }
                return $cast:L($nextident:L), nil
                """,
                MapUtils.of(
                        "errorf", GoStdlibTypes.Fmt.Errorf,
                        "ident", ident,
                        "nextident", ident + "_",
                        "min", min,
                        "max", max,
                        "cast", castTo
                ));
    }

    private GoWriter.Writable generateStructFieldDeref(MemberShape member, String ident) {
        var symbol = symbolProvider.toSymbol(member);
        if (!isPointable(symbol)) {
            return goTemplate(ident);
        }
        return switch (model.expectShape(member.getTarget()).getType()) {
            case BYTE -> goTemplate("$T($L)", SmithyGoTypes.Ptr.Int8, ident);
            case SHORT -> goTemplate("$T($L)", SmithyGoTypes.Ptr.Int16, ident);
            case INTEGER -> goTemplate("$T($L)", SmithyGoTypes.Ptr.Int32, ident);
            case LONG -> goTemplate("$T($L)", SmithyGoTypes.Ptr.Int64, ident);
            case STRING -> goTemplate("$T($L)", SmithyGoTypes.Ptr.String, ident);
            case BOOLEAN -> goTemplate("$T($L)", SmithyGoTypes.Ptr.Bool, ident);
            default -> goTemplate(ident);
        };
    }

    private GoWriter.Writable generateSerializeError() {
        return goTemplate("""
                func serializeError(w $rw:T, err error) {
                    if _, ok := err.(*$notImplemented:L); ok {
                        writeEmpty(w, http.StatusNotImplemented)
                        return
                    }

                    writeEmpty(w, http.StatusInternalServerError)
                }

                func writeEmpty(w $rw:T, status int) {
                    w.WriteHeader(status)
                    w.Write([]byte("{}"))
                }
                """,
                MapUtils.of(
                        "rw", GoStdlibTypes.Net.Http.ResponseWriter,
                        "notImplemented", NotImplementedError.NAME
                ));
    }

    @Override
    public GoWriter.Writable generateHttpHandler() {
        return goTemplate("""
                type httpHandler struct{
                    service $interface:L
                }

                var _ $handler:T = (*httpHandler)(nil)

                $serveHttp:W
                """,
                MapUtils.of(
                        "interface", ServerInterface.NAME,
                        "handler", GoStdlibTypes.Net.Http.Handler,
                        "serveHttp", generateServeHttp()
                ));
    }

    private GoWriter.Writable generateServeHttp() {
        return goTemplate("""
                func (h *httpHandler) ServeHTTP(w $rw:T, r $r:P) {
                    w.Header().Set("Content-Type", "application/x-amz-json-1.0")

                    if r.Method != http.MethodPost {
                        writeEmpty(w, http.StatusNotFound)
                        return
                    }

                    target := r.Header.Get("X-Amz-Target")
                    $route:W

                    writeEmpty(w, http.StatusNotFound)
                }
                """,
                MapUtils.of(
                        "rw", GoStdlibTypes.Net.Http.ResponseWriter,
                        "r", GoStdlibTypes.Net.Http.Request,
                        "route", generateRouteRequest()
                ));
    }

    private GoWriter.Writable generateRouteRequest() {
        return GoWriter.ChainWritable.of(
                TopDownIndex.of(model).getContainedOperations(service).stream()
                        .filter(op -> !ServerCodegenUtils.operationHasEventStream(
                            model, operationIndex.expectInputShape(op), operationIndex.expectOutputShape(op)))
                        .map(it -> goTemplate("""
                                if target == $S {
                                    $W
                                }
                                """, getOperationTarget(it), generateHandleOperation(it)))
                        .toList()
        ).compose(false);
    }

    private String getOperationTarget(OperationShape operation) {
        return service.getId().getName(service) + "." + operation.getId().getName(service);
    }

    private GoWriter.Writable generateHandleOperation(OperationShape operation) {
        return goTemplate("""
                d := $decoder:T(r.Body)
                d.UseNumber()
                var jv map[string]interface{}
                if err := d.Decode(&jv); err != nil {
                    serializeError(w, err)
                    return
                }

                in, err := $deserialize:L(jv)
                if err != nil {
                    serializeError(w, err)
                    return
                }

                _, err = h.service.$operation:L(r.Context(), in)
                if err != nil {
                    serializeError(w, err)
                    return
                }

                writeEmpty(w, http.StatusOK)
                return
                """,
                MapUtils.of(
                        "decoder", GoStdlibTypes.Encoding.Json.NewDecoder,
                        "deserialize", getDeserializerName(model.expectShape(operation.getInputShape())),
                        "target", getOperationTarget(operation),
                        "operation", symbolProvider.toSymbol(operation).getName()
                ));
    }
}
