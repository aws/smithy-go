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

import java.util.List;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.service.GoServiceIntegration;
import software.amazon.smithy.go.codegen.service.ServiceProtocolGenerator;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public class SupportServiceAwsJson10Protocol implements GoServiceIntegration {
    @Override
    public List<ServiceProtocolGenerator> getServerProtocolGenerators(
        Model model,
        ServiceShape service,
        SymbolProvider symbolProvider
    ) {
        return List.of(new AwsJson10ProtocolGenerator(model, service, symbolProvider));
    }
}
