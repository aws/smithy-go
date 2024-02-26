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

package software.amazon.smithy.go.codegen.server;

import java.util.Set;
import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public interface ServerProtocolGenerator {
    // Smithy
    ApplicationProtocol getApplicationProtocol();

    ShapeId getProtocol();

    // Go
    GoWriter.Writable generateHandleRequest();

    GoWriter.Writable generateHandleOperation(OperationShape operation);

    GoWriter.Writable generateOptions();

    GoWriter.Writable generateDeserializers(Set<Shape> shape);

    GoWriter.Writable generateSerializers(Set<Shape> shape);

    GoWriter.Writable generateProtocolSource();
}
