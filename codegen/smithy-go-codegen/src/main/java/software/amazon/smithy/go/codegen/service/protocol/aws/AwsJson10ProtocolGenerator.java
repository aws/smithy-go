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

import software.amazon.smithy.aws.traits.protocols.AwsJson1_0Trait;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.service.NotImplementedError;
import software.amazon.smithy.go.codegen.service.ServiceInterface;
import software.amazon.smithy.go.codegen.service.protocol.HttpServerProtocolGenerator;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
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

    public AwsJson10ProtocolGenerator(Model model, ServiceShape service, SymbolProvider symbolProvider) {
        this.model = model;
        this.service = service;
        this.symbolProvider = symbolProvider;
    }

    @Override
    public ShapeId getProtocol() {
        return AwsJson1_0Trait.ID;
    }


    @Override
    public GoWriter.Writable generateSource() {
        return GoWriter.ChainWritable.of(
                super.generateSource(),
                generateSerializeError()
        ).compose();
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
                        "interface", ServiceInterface.NAME,
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
                w.Header().Set("X-Amz-Target", $target:S)
                _, err := h.service.$operation:T(r.Context(), nil)
                if err != nil {
                    serializeError(w, err)
                    return
                }

                writeEmpty(w, http.StatusOK)
                return
                """,
                MapUtils.of(
                        "target", getOperationTarget(operation),
                        "operation", symbolProvider.toSymbol(operation)
                ));
    }
}
