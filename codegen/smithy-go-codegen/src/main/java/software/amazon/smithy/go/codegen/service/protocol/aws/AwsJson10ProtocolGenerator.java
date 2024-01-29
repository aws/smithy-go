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

import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.service.protocol.HttpProtocolGenerator;
import software.amazon.smithy.utils.MapUtils;

/**
 * Implements the aws.protocols#awsJson1_0 protocol.
 */
public final class AwsJson10ProtocolGenerator extends HttpProtocolGenerator {
    @Override
    public GoWriter.Writable generateHttpHandler() {
        return goTemplate("""
                type httpHandler struct{}

                var _ $handler:T = (*httpHandler)(nil)

                $serveHttp:W
                """,
                MapUtils.of(
                        "handler", GoStdlibTypes.Net.Http.Handler,
                        "serveHttp", generateServeHttp()
                ));
    }

    private GoWriter.Writable generateServeHttp() {
        return goTemplate("""
                func (h *httpHandler) ServeHTTP(w $rw:T, r $r:P) {
                    w.WriteHeader(http.StatusTeapot) // TODO
                }
                """,
                MapUtils.of(
                        "rw", GoStdlibTypes.Net.Http.ResponseWriter,
                        "r", GoStdlibTypes.Net.Http.Request
                ));
    }
}
