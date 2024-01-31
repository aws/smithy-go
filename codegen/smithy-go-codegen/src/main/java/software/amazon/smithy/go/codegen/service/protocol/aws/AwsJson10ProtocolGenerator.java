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

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.service.protocol.JsonDeserializerGenerator.getDeserializerName;
import static software.amazon.smithy.go.codegen.service.protocol.JsonSerializerGenerator.getSerializerName;

import java.util.Set;
import software.amazon.smithy.aws.traits.protocols.AwsJson1_0Trait;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.service.NotImplementedError;
import software.amazon.smithy.go.codegen.service.RequestHandler;
import software.amazon.smithy.go.codegen.service.ServerCodegenUtils;
import software.amazon.smithy.go.codegen.service.protocol.HttpHandlerProtocolGenerator;
import software.amazon.smithy.go.codegen.service.protocol.JsonDeserializerGenerator;
import software.amazon.smithy.go.codegen.service.protocol.JsonSerializerGenerator;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.traits.HttpErrorTrait;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Implements the aws.protocols#awsJson1_0 protocol.
 */
@SmithyInternalApi
public final class AwsJson10ProtocolGenerator extends HttpHandlerProtocolGenerator {
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
    public GoWriter.Writable generateDeserializers(Set<Shape> shapes) {
        return new JsonDeserializerGenerator(model, symbolProvider).generate(shapes);
    }

    @Override
    public GoWriter.Writable generateSerializers(Set<Shape> shapes) {
        return GoWriter.ChainWritable.of(
                new JsonSerializerGenerator(model, symbolProvider).generate(shapes),
                generateSerializeError()
        ).compose();
    }

    @Override
    public GoWriter.Writable generateServeHttp() {
        return goTemplate("""
                func (h *$requestHandler:L) ServeHTTP(w $rw:T, r $r:P) {
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
                        "requestHandler", RequestHandler.NAME,
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

                out, err := h.service.$operation:L(r.Context(), in)
                if err != nil {
                    serializeError(w, err)
                    return
                }

                e := $encoder:T()
                if err := $serialize:L(out, e.Value); err != nil {
                    serializeError(w, err)
                    return
                }

                w.WriteHeader(http.StatusOK)
                w.Write(e.Bytes())
                return
                """,
                MapUtils.of(
                        "decoder", GoStdlibTypes.Encoding.Json.NewDecoder,
                        "deserialize", getDeserializerName(model.expectShape(operation.getInputShape())),
                        "operation", symbolProvider.toSymbol(operation).getName(),
                        "encoder", SmithyGoTypes.Encoding.Json.NewEncoder,
                        "serialize", getSerializerName(model.expectShape(operation.getOutputShape()))
                ));
    }

    private GoWriter.Writable generateSerializeError() {
        var errorShapes = model.getStructureShapesWithTrait(ErrorTrait.class);
        return goTemplate("""
                func serializeError(w $rw:T, err error) {
                    if _, ok := err.(*$notImplemented:L); ok {
                        writeEmpty(w, http.StatusNotImplemented)
                        return
                    }

                    $serializeErrors:W

                    writeEmpty(w, http.StatusInternalServerError)
                }

                func writeEmpty(w $rw:T, status int) {
                    w.WriteHeader(status)
                    w.Write([]byte("{}"))
                }
                """,
                MapUtils.of(
                        "rw", GoStdlibTypes.Net.Http.ResponseWriter,
                        "notImplemented", NotImplementedError.NAME,
                        "serializeErrors", generateSerializeErrors(errorShapes)
                ));
    }

    private GoWriter.Writable generateSerializeErrors(Set<StructureShape> errorShapes) {
        return GoWriter.ChainWritable.of(
                errorShapes.stream()
                        .map(this::generateSerializeError)
                        .toList()
        ).compose(false);
    }

    private GoWriter.Writable generateSerializeError(StructureShape errorShape) {
        var httpStatus = errorShape.hasTrait(HttpErrorTrait.class)
                ? errorShape.expectTrait(HttpErrorTrait.class).getCode()
                : 400;
        return goTemplate("""
                if _, ok := err.($err:P); ok {
                    w.WriteHeader($status:L)
                    w.Write([]byte(`{"__type":$type:S}`))
                    return
                }
                """,
                MapUtils.of(
                        "err", symbolProvider.toSymbol(errorShape),
                        "status", httpStatus,
                        "type", errorShape.getId().toString()
                ));
    }

    private String getOperationTarget(OperationShape operation) {
        return service.getId().getName(service) + "." + operation.getId().getName(service);
    }
}
