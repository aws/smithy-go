/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.go.codegen.integration;

import java.util.List;
import software.amazon.smithy.go.codegen.auth.AuthParameter;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.utils.ListUtils;

/**
 * Adds the input region as an auth resolution parameter for SigV4x-based services.
 */
public class SigV4AuthParameters implements GoIntegration {
    private boolean isSigV4Service(Model model, ServiceShape service) {
        return service.hasTrait("aws.auth#sigv4");
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return ListUtils.of(
                RuntimeClientPlugin.builder()
                        .servicePredicate(this::isSigV4Service)
                        .addAuthParameter(AuthParameter.REGION)
                        .build()
        );
    }
}
