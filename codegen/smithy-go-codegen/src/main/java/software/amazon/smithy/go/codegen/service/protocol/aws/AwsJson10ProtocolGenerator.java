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

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.service.protocol.JsonDeserializerGenerator.getDeserializerName;
import static software.amazon.smithy.go.codegen.service.protocol.JsonSerializerGenerator.getSerializerName;

import java.util.Set;
import software.amazon.smithy.aws.traits.protocols.AwsJson1_0Trait;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.knowledge.GoValidationIndex;
import software.amazon.smithy.go.codegen.service.NotImplementedError;
import software.amazon.smithy.go.codegen.service.RequestHandler;
import software.amazon.smithy.go.codegen.service.ServiceCodegenUtils;
import software.amazon.smithy.go.codegen.service.ServiceValidationGenerator;
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
    private final GoValidationIndex validationIndex;

    public AwsJson10ProtocolGenerator(Model model, ServiceShape service, SymbolProvider symbolProvider) {
        this.model = model;
        this.service = service;
        this.symbolProvider = symbolProvider;

        this.operationIndex = OperationIndex.of(model);
        this.validationIndex = GoValidationIndex.of(model);
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

    // TODO this does too much, HTTP operation handling should be static apart from the protocol itself and receive
    //      serde context
    @Override
    public GoWriter.Writable generateServeHttp() {
        return goTemplate("""
                func (h *$requestHandler:L) ServeHTTP(w $rw:T, r $r:P) {
                    id, err := $newUuid:T($rand:T).GetUUID()
                    if err != nil {
                        serializeError(w, err)
                        return
                    }

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
                        "newUuid", SmithyGoTypes.Rand.NewUUID,
                        "rand", GoStdlibTypes.Crypto.Rand.Reader,
                        "requestHandler", RequestHandler.NAME,
                        "rw", GoStdlibTypes.Net.Http.ResponseWriter,
                        "r", GoStdlibTypes.Net.Http.Request,
                        "route", generateRouteRequest()
                ));
    }

    private GoWriter.Writable generateRouteRequest() {
        return GoWriter.ChainWritable.of(
                TopDownIndex.of(model).getContainedOperations(service).stream()
                        .filter(op -> !ServiceCodegenUtils.operationHasEventStream(
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
        var input = model.expectShape(operation.getInputShape());
        var output = model.expectShape(operation.getOutputShape());
        return goTemplate("""
                $beforeDeserialize:W
                $deserialize:W
                $afterDeserialize:W

                $validate:W

                out, err := h.service.$operation:L(r.Context(), in)
                if err != nil {
                    serializeError(w, err)
                    return
                }

                $beforeSerialize:W
                $beforeWriteResponse:W
                $serialize:W
                """,
                MapUtils.of(
                        "deserialize", generateDeserialize(input),
                        "validate", validationIndex.operationRequiresValidation(service, operation)
                                ? generateValidateInput(input)
                                : emptyGoTemplate(),
                        "operation", symbolProvider.toSymbol(operation).getName(),
                        "serialize", generateSerialize(output),
                        "beforeDeserialize", generateInvokeInterceptor("BeforeDeserialize", "r"),
                        "afterDeserialize", generateInvokeInterceptor("AfterDeserialize", "in"),
                        "beforeSerialize", generateInvokeInterceptor("BeforeSerialize", "out"),
                        "beforeWriteResponse", generateInvokeInterceptor("BeforeWriteResponse", "w")
                ));
    }

    private GoWriter.Writable generateInvokeInterceptor(String type, String args) {
        return goTemplate("""
                for _, i := range h.options.Interceptors.$1L {
                    if err := i.$1L(r.Context(), id, $2L); err != nil {
                        serializeError(w, err)
                        return
                    }
                }
                """, type, args);
    }

    private GoWriter.Writable generateDeserialize(Shape input) {
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
                """,
                MapUtils.of(
                        "decoder", GoStdlibTypes.Encoding.Json.NewDecoder,
                        "deserialize", getDeserializerName(input)
                ));
    }

    private GoWriter.Writable generateValidateInput(Shape input) {
        return goTemplate("""
                if err := $L(in); err != nil {
                    serializeError(w, err)
                    return
                }
                """, ServiceValidationGenerator.getShapeValidatorName(input));
    }

    private GoWriter.Writable generateSerialize(Shape output) {
        return goTemplate("""
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
                        "encoder", SmithyGoTypes.Encoding.Json.NewEncoder,
                        "serialize", getSerializerName(output)
                ));
    }

    private GoWriter.Writable generateSerializeError() {
        var errorShapes = model.getStructureShapesWithTrait(ErrorTrait.class);
        return goTemplate("""
                func serializeError(w $rw:T, err error) {
                    if _, ok := err.($invalidParams:T); ok {
                        w.WriteHeader(http.StatusBadRequest)
                        w.Write([]byte(`{"__type":"InvalidRequest"}`))
                        return
                    }
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
                        "invalidParams", SmithyGoTypes.Smithy.InvalidParamsError,
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
