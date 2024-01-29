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

package software.amazon.smithy.go.codegen.service;

import software.amazon.smithy.go.codegen.GoWriter;

public interface ProtocolGenerator {
    /**
     * Generate all supporting source code required by this protocol.
     */
    GoWriter.Writable generateSource();

    /**
     * Generate transport fields.
     * Called within the scope of the service's concrete struct declaration.
     */
    GoWriter.Writable generateTransportFields();

    /**
     * Generate transport options.
     * Called within the scope of the service's concrete struct options declaration.
     */
    GoWriter.Writable generateTransportOptions();

    /**
     * Generate transport initialization.
     * Called within the scope of the service's concrete struct New() method. The service is in scope.
     */
    GoWriter.Writable generateTransportInit();

    /**
     * Generate code to start serving traffic with the protocol transport.
     * Called within the scope of the service's concrete struct Run() method.
     */
    GoWriter.Writable generateTransportRun();

    // TODO generateTransportShutdown()
}
