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

package software.amazon.smithy.go.codegen.protocol.rpc2;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.go.codegen.EventStreamGenerator;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.go.codegen.protocol.SerializeRequestMiddleware;
import software.amazon.smithy.model.knowledge.EventStreamIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public abstract class Rpc2SerializeRequestMiddleware extends SerializeRequestMiddleware {
    private final EventStreamIndex eventStreamIndex;

    protected Rpc2SerializeRequestMiddleware(
            ProtocolGenerator generator, ProtocolGenerator.GenerationContext ctx, OperationShape operation
    ) {
        super(generator, ctx, operation);

        this.eventStreamIndex = EventStreamIndex.of(ctx.getModel());
    }

    public abstract String getProtocolName();

    public abstract String getContentType();

    @Override
    public final GoWriter.Writable generateRouteRequest() {
        return goTemplate("""
                req.Method = $methodPost:T
                req.URL.Path = "/service/$service:L/operation/$operation:L"
                req.Header.Set("smithy-protocol", $protocol:S)

                $typeHeaders:W
                """,
                MapUtils.of(
                        "methodPost", GoStdlibTypes.Net.Http.MethodPost,
                        "service", ctx.getService().getId().getName(),
                        "operation", operation.getId().getName(),
                        "protocol", getProtocolName(),
                        "typeHeaders", generateTypeHeaders()
                ));
    }

    private GoWriter.Writable generateTypeHeaders() {
        return goTemplate("""
                req.Header.Set("Content-Type", $1S)
                req.Header.Set("Accept", $1S)
                """, isEventStream() ? EventStreamGenerator.AMZ_CONTENT_TYPE : getContentType());
    }

    private boolean isEventStream() {
        return eventStreamIndex.getInputInfo(operation).isPresent()
                || eventStreamIndex.getOutputInfo(operation).isPresent();
    }
}
