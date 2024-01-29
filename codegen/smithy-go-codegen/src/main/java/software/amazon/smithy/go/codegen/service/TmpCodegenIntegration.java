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

import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.go.codegen.service.protocol.aws.AwsJson10ProtocolGenerator;
import software.amazon.smithy.model.Model;

// TODO: setup invocation of codegen via cli, remove this
public class TmpCodegenIntegration implements GoIntegration {
    @Override
    public void writeAdditionalFiles(
            GoSettings settings, Model model, SymbolProvider symbolProvider, GoDelegator goDelegator
    ) {
        // TODO should be resolved from model
        final var protocolGenerator = new AwsJson10ProtocolGenerator();

        final var service = settings.getService(model);
        goDelegator.useFileWriter("feat_svcgen.go", settings.getModuleName(), GoWriter.ChainWritable.of(
                protocolGenerator.generateSource(),
                new ServiceInterface(model, service, symbolProvider),
                new NoopServiceStruct(model, service, symbolProvider),
                new NotImplementedError(),
                new ServiceStruct(protocolGenerator)
        ).compose());
    }
}
