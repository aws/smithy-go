/*
 * Copyright 2023 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.go.codegen;

import software.amazon.smithy.model.shapes.ServiceShape;

/**
 * Used by integrations to generate authentication
 * scheme resolution.
 *
 */
public interface AuthenticationSchemeGenerator {

    /**
     * Used by integrations to generate authentication
     * scheme resolution at endpoint resolution time.
     * Method to be deprecated (removed if not GA) when
     * authentication scheme resolution is separated from
     * endpoint resolution.
     *
     * @param writer writer that will be used for generation.
     */
    default void renderEndpointBasedAuthSchemeResolution(GoWriter writer, ServiceShape serviceShape) {
        // pass
    }

}
