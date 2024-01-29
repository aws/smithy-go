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

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.service.ProtocolGenerator;

/**
 * Implements base transport codegen for HTTP protocols.
 */
public abstract class HttpProtocolGenerator implements ProtocolGenerator {
    @Override
    public GoWriter.Writable generateSource() {
        return generateHttpHandler();
    }

    @Override
    public GoWriter.Writable generateTransportFields() {
        return goTemplate("""
                server $P
                """, GoStdlibTypes.Net.Http.Server);
    }

    @Override
    public GoWriter.Writable generateTransportOptions() {
        return emptyGoTemplate();
    }

    @Override
    public GoWriter.Writable generateTransportInit() {
        return goTemplate("""
                sv.server = &$T{
                    Handler: &httpHandler{svc},
                }
                """, GoStdlibTypes.Net.Http.Server);
    }

    @Override
    public GoWriter.Writable generateTransportRun() {
        return goTemplate("""
                return sv.server.ListenAndServe()
                """);
    }

    /**
     * Generates the HTTP handler expected by this base class.
     * The generated declaration MUST be `type httpHandler struct` and this struct MUST implement the net/http Handler
     * interface.
     */
    public abstract GoWriter.Writable generateHttpHandler();
}
