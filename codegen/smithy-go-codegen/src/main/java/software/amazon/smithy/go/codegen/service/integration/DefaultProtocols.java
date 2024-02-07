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

package software.amazon.smithy.go.codegen.service.integration;

import java.util.List;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.service.GoServiceIntegration;
import software.amazon.smithy.go.codegen.service.ServiceProtocolGenerator;
import software.amazon.smithy.go.codegen.service.protocol.aws.AwsJson10ProtocolGenerator;
import software.amazon.smithy.utils.ListUtils;

public class DefaultProtocols implements GoServiceIntegration {
    @Override
    public List<ServiceProtocolGenerator> getProtocolGenerators(GoCodegenContext ctx) {
        return ListUtils.of(
                new AwsJson10ProtocolGenerator(ctx)
        );
    }
}
