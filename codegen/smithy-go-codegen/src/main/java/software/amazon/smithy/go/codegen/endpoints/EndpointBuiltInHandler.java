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

package software.amazon.smithy.go.codegen.endpoints;

import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.rulesengine.language.syntax.parameters.Parameters;

/**
 * Used by integrations to provide BuiltIn value resolution
 * functionality during endpoint resolution.
 *
 */
public interface EndpointBuiltInHandler {

    /**
     * Used by integrations to set a member on the endpoints resolution
     * middleware object.
     *
     * @param writer Settings used to generate.
     */
    default void renderEndpointBuiltInField(GoWriter writer) {
        // pass
    }

    /**
     * Used by integrations to set invoke BuiltIn resolution during Endpoint
     * resolution.
     *
     * @param writer Settings used to generate.
     */
    default void renderEndpointBuiltInInvocation(GoWriter writer) {
        // pass
    }


    /**
     * Used by integrations to set initialize BuiltIn values on the Endpoint
     * resolution object.
     *
     * @param writer Settings used to generate.
     */
    default void renderEndpointBuiltInInitialization(GoWriter writer, Parameters parameters) {
        // pass
    }
}
