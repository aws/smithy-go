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
import static software.amazon.smithy.go.codegen.SymbolUtils.isPointable;
import static software.amazon.smithy.go.codegen.service.Util.getShapesToSerde;

import software.amazon.smithy.aws.traits.protocols.AwsJson1_0Trait;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
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
        return "awsJson10_deserialize" + symbolProvider.toSymbol(shape).getName();
    }

    private String getSerializerName(Shape shape) {
        return "awsJson10_serialize" + symbolProvider.toSymbol(shape).getName();
    }

    private GoWriter.Writable generateDeserializers() {
        return GoWriter.ChainWritable.of(
                TopDownIndex.of(model).getContainedOperations(service).stream()
                        .map(it -> model.expectShape(it.getInputShape(), StructureShape.class))
                        .flatMap(it -> getShapesToSerde(model, it).stream())
                        .collect(toSet()).stream()
                        .map(this::generateDeserializeFunc)
                        .toList()
        ).compose();
    }

    private GoWriter.Writable generateDeserializeFunc(StructureShape structure) {
        var fields = GoWriter.ChainWritable.of(
                structure.getAllMembers().entrySet().stream()
                        .map(it -> goTemplate("""
                                if k == $S {
                                    $W
                                }
                                """, it.getKey(), generateDeserializeStructField(it.getValue())))
                        .toList()
        );
        return goTemplate("""
                func $name:L(jv map[string]interface{}) ($struct:P, error) {
                    v := &$struct:T{}
                    for k, jvv := range jv {
                        $fields:W
                    }
                    return v, nil
                }
                """,
                MapUtils.of(
                        "name", getDeserializerName(structure),
                        "struct", symbolProvider.toSymbol(structure),
                        "fields", fields.compose(false)
                ));
    }

    private GoWriter.Writable generateDeserializeStructField(MemberShape member) {
        return switch (model.expectShape(member.getTarget()).getType()) {
            case BOOLEAN -> generateDeserializePrimitive(member, SmithyGoTypes.Ptr.Bool, "bool");
            case STRING -> generateDeserializePrimitive(member, SmithyGoTypes.Ptr.String, "string");
            case BYTE -> generateDeserializeIntegral(member, SmithyGoTypes.Ptr.Int8, "int8",
                    Byte.MIN_VALUE, Byte.MAX_VALUE);
            case SHORT -> generateDeserializeIntegral(member, SmithyGoTypes.Ptr.Int16, "int16",
                    Short.MIN_VALUE, Short.MAX_VALUE);
            case INTEGER -> generateDeserializeIntegral(member, SmithyGoTypes.Ptr.Int32, "int32",
                    Integer.MIN_VALUE, Integer.MAX_VALUE);
            case LONG -> generateDeserializeIntegral(member, SmithyGoTypes.Ptr.Int64, "int64",
                    Long.MIN_VALUE, Long.MAX_VALUE);
            case STRUCTURE -> goTemplate("""
                    av, ok := jvv.(map[string]interface{})
                    if !ok {
                        return nil, $errorf:T("invalid")
                    }

                    var err error
                    v.$field:L, err = $deserialize:L(av)
                    if err != nil {
                        return nil, $errorf:T("invalid")
                    }
                    """,
                    MapUtils.of(
                            "errorf", GoStdlibTypes.Fmt.Errorf,
                            "field", symbolProvider.toMemberName(member),
                            "deserialize", getDeserializerName(model.expectShape(member.getTarget()))
                    ));
            case LIST, SET -> {
                var list = model.expectShape(member.getTarget(), CollectionShape.class);
                var listMemberTarget = model.expectShape(list.getMember().getTarget());
                yield goTemplate("""
                                jsonList, ok := jvv.([]interface{})
                                if !ok {
                                    return nil, $errorf:T("invalid")
                                }
                                deserializedList := []$item:T{}
                                for _, jsonItem := range jsonList {
                                    $deserialize:W
                                }
                                v.$field:L = deserializedList
                                """,
                        MapUtils.of(
                                "errorf", GoStdlibTypes.Fmt.Errorf,
                                "item", symbolProvider.toSymbol(listMemberTarget),
                                "field", symbolProvider.toMemberName(member),
                                "deserialize", generateDeserializeListItem(member, listMemberTarget)
                        ));
            }
            case MAP -> {
                var map = model.expectShape(member.getTarget(), MapShape.class);
                var mapValueTarget = model.expectShape(map.getValue().getTarget());
                yield goTemplate("""
                                jsonMap, ok := jvv.(map[string]interface{})
                                if !ok {
                                    return nil, $errorf:T("invalid")
                                }
                                deserializedMap := map[string]$item:T{}
                                for jsonMapKey, jsonMapValue := range jsonMap {
                                    $deserialize:W
                                }
                                v.$field:L = deserializedMap
                                """,
                        MapUtils.of(
                                "errorf", GoStdlibTypes.Fmt.Errorf,
                                // TODO blind $P isn't reliable here, nullability of value target needs to correlate
                                //      back to generated field
                                "item", symbolProvider.toSymbol(mapValueTarget),
                                "field", symbolProvider.toMemberName(member),
                                "deserialize", generateDeserializeMapEntry(member, mapValueTarget)
                        ));
            }
            case BLOB -> goTemplate("""
                    av, ok := jvv.(string)
                    if !ok {
                        return nil, $errorf:T("invalid")
                    }
                    p, err := $b64:T.DecodeString(av)
                    if err != nil {
                        return nil, err
                    }
                    v.$field:L = p
                    """,
                    MapUtils.of(
                            "errorf", GoStdlibTypes.Fmt.Errorf,
                            "b64", GoStdlibTypes.Encoding.Base64.StdEncoding,
                            "field", symbolProvider.toMemberName(member)
                    ));
          //case TIMESTAMP -> null;
          //case FLOAT -> null;
          //case DOCUMENT -> null;
          //case DOUBLE -> null;
          //case BIG_DECIMAL -> null;
          //case BIG_INTEGER -> null;
          //case ENUM -> null;
          //case INT_ENUM -> null;
          //case UNION -> null;
            case MEMBER, SERVICE, RESOURCE, OPERATION -> throw new CodegenException("cannot deserialize");
            default -> goTemplate("// TODO");
        };
    }

    private GoWriter.Writable generateDeserializeListItem(MemberShape member, Shape target) {
        return switch (target.getType()) {
            case STRUCTURE -> goTemplate("""
                    jsonStruct, ok := jsonItem.(map[string]interface{})
                    if !ok {
                        return nil, $errorf:T("invalid")
                    }
                    deserializedStruct, err := $deserialize:L(jsonStruct)
                    if err != nil {
                        return nil, $errorf:T("invalid")
                    }
                    deserializedList = append(deserializedList, $expr:L)
                    """,
                    MapUtils.of(
                            "errorf", GoStdlibTypes.Fmt.Errorf,
                            "deserialize", getDeserializerName(target),
                            // TODO this needs to check if the list's member is ptr and dereference conditionally
                            "expr", "*deserializedStruct"
                    ));
            case STRING -> goTemplate("""
                    av, ok := jsonItem.(string)
                    if !ok {
                        return nil, $T("invalid")
                    }
                    deserializedList = append(deserializedList, av)
                    """, GoStdlibTypes.Fmt.Errorf);
            case BLOB -> goTemplate("""
                    av, ok := jsonItem.(string)
                    if !ok {
                        return nil, $errorf:T("invalid")
                    }
                    p, err := $b64:T.DecodeString(av)
                    if err != nil {
                        return nil, err
                    }
                    deserializedList = append(deserializedList, p)
                    """,
                    MapUtils.of(
                            "errorf", GoStdlibTypes.Fmt.Errorf,
                            "b64", GoStdlibTypes.Encoding.Base64.StdEncoding
                    ));
            case ENUM -> goTemplate("""
                    av, ok := jsonItem.(string)
                    if !ok {
                        return nil, $T("invalid")
                    }
                    deserializedList = append(deserializedList, $T(av))
                    """, GoStdlibTypes.Fmt.Errorf, symbolProvider.toSymbol(target));
          //case BOOLEAN -> null;
          //case TIMESTAMP -> null;
          //case BYTE -> null;
          //case SHORT -> null;
          //case INTEGER -> null;
          //case LONG -> null;
          //case FLOAT -> null;
          //case DOCUMENT -> null;
          //case DOUBLE -> null;
          //case BIG_DECIMAL -> null;
          //case BIG_INTEGER -> null;
          //case INT_ENUM -> null;
          //case UNION -> null;
            case LIST, SET ->
                    throw new CodegenException("recursive list/set will not work right now, idents will overlap");
            case MAP ->
                    throw new CodegenException("recursive map will not work right now, idents will overlap");
            case MEMBER, SERVICE, RESOURCE, OPERATION ->
                    throw new CodegenException("cannot deserialize " + target.getType());
            default -> goTemplate("// TODO " + target.getType());
        };
    }

    private GoWriter.Writable generateDeserializeMapEntry(MemberShape member, Shape target) {
        return switch (target.getType()) {
            case STRUCTURE -> goTemplate("""
                    mv, ok := jsonMapValue.(map[string]interface{})
                    if !ok {
                        return nil, $errorf:T("invalid")
                    }
                    deserializedStruct, err := $deserialize:L(mv)
                    if err != nil {
                        return nil, err
                    }
                    deserializedMap[jsonMapKey] = $expr:L
                    """,
                    MapUtils.of(
                            "errorf", GoStdlibTypes.Fmt.Errorf,
                            "deserialize", getDeserializerName(target),
                            // TODO this needs to check if the map's value is ptr and dereference conditionally
                            "expr", "*deserializedStruct"
                    ));
            case STRING -> goTemplate("""
                            stringValue, ok := jsonMapValue.(string)
                            if !ok {
                                return nil, $T("invalid")
                            }
                            deserializedMap[jsonMapKey] = stringValue
                            """, GoStdlibTypes.Fmt.Errorf);
          //case BLOB -> null;
          //case BOOLEAN -> null;
          //case TIMESTAMP -> null;
          //case BYTE -> null;
          //case SHORT -> null;
          //case INTEGER -> null;
          //case LONG -> null;
          //case FLOAT -> null;
          //case DOCUMENT -> null;
          //case DOUBLE -> null;
          //case BIG_DECIMAL -> null;
          //case BIG_INTEGER -> null;
          //case ENUM -> null;
          //case INT_ENUM -> null;
          //case STRUCTURE -> null;
          //case UNION -> null;
            case LIST, SET ->
                    throw new CodegenException("recursive list/set will not work right now, idents will overlap");
            case MAP ->
                    throw new CodegenException("recursive map will not work right now, idents will overlap");
            case MEMBER, SERVICE, RESOURCE, OPERATION ->
                    throw new CodegenException("cannot deserialize " + target.getType());
            default -> goTemplate("// TODO " + target.getType());
        };
    }

    private GoWriter.Writable generateDeserializePrimitive(MemberShape member, Symbol deref, String type) {
        return goTemplate("""
                av, ok := jvv.($type:L)
                if !ok {
                    return nil, $errorf:T("invalid")
                }
                v.$field:L = $expr:W
                """,
                MapUtils.of(
                        "type", type,
                        "errorf", GoStdlibTypes.Fmt.Errorf,
                        "field", symbolProvider.toMemberName(member),
                        "expr", generatePrimitiveExpr(symbolProvider.toSymbol(member), deref, "av")
                ));
    }

    private GoWriter.Writable generateDeserializeIntegral(
            MemberShape member, Symbol deref, String cast, long min, long max
    ) {
        return goTemplate("""
                nv, ok := jvv.($number:T)
                if !ok {
                    return nil, $errorf:T("invalid")
                }
                av, err := nv.Int64()
                if err != nil {
                    return nil, $errorf:T("invalid")
                }
                if av < $min:L || av > $max:L {
                    return nil, $errorf:T("invalid")
                }

                v.$field:L = $expr:W
                """,
                MapUtils.of(
                        "number", GoStdlibTypes.Encoding.Json.Number,
                        "errorf", GoStdlibTypes.Fmt.Errorf,
                        "field", symbolProvider.toMemberName(member),
                        "expr", generatePrimitiveExpr(symbolProvider.toSymbol(member), deref, cast + "(av)"),
                        "min", min,
                        "max", max
                ));
    }

    private GoWriter.Writable generatePrimitiveExpr(Symbol symbol, Symbol deref, String ident) {
        return isPointable(symbol) ? goTemplate("$T($L)", deref, ident) : goTemplate(ident);
    }

    private GoWriter.Writable generateSerializers() {
        return GoWriter.ChainWritable.of(
                TopDownIndex.of(model).getContainedOperations(service).stream()
                        .map(it -> model.expectShape(it.getOutputShape(), StructureShape.class))
                        .flatMap(it -> getShapesToSerde(model, it).stream())
                        .map(this::generateSerializeStructure)
                        .collect(toSet())
        ).compose();
    }

    private GoWriter.Writable generateSerializeStructure(StructureShape structure) {
        return goTemplate("""
                func $name:L(v $struct:P) map[string]interface{} {
                    jv := map[string]interface{}{}
                    return jv
                }
                """,
                MapUtils.of(
                        "name", getSerializerName(structure),
                        "struct", symbolProvider.toSymbol(structure)
                ));
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
