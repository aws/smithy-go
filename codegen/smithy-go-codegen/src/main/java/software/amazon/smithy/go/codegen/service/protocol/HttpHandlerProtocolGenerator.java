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

package software.amazon.smithy.go.codegen.service.protocol;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.service.RequestHandler;
import software.amazon.smithy.go.codegen.service.ServerProtocolGenerator;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Base class for HTTP protocol codegen.
 * HTTP protocols serve requests by generating a net/http.Handler implementation onto the base RequestHandler struct.
 */
@SmithyInternalApi
public abstract class HttpHandlerProtocolGenerator implements ServerProtocolGenerator {
    @Override
    public ApplicationProtocol getApplicationProtocol() {
        return ApplicationProtocol.createDefaultHttpApplicationProtocol();
    }

    @Override
    public GoWriter.Writable generateHandleRequest() {
        return goTemplate("""
            var _ $httpHandler:T = (*$requestHandler:L)(nil)

            $serveHttp:W
            """,
                MapUtils.of(
                        "requestHandler", RequestHandler.NAME,
                        "httpHandler", GoStdlibTypes.Net.Http.Handler,
                        "serveHttp", generateServeHttp()
                ));
    }

    @Override
    public GoWriter.Writable generateOptions() {
        // TODO interceptors
        return goTemplate("""
                // TODO: HTTP interceptors
                """);
    }

    /**
     * Generates the net/http.Handler's ServeHTTP implementation for this protocol.
     */
    public abstract GoWriter.Writable generateServeHttp();
}
