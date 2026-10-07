/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static software.amazon.smithy.go.codegen.TestUtils.buildMockPluginContext;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.go.codegen.integration.Waiters2;
import software.amazon.smithy.build.MockManifest;
import software.amazon.smithy.model.Model;

public class WaiterTimeoutTest {
    @Test
    public void stopsBeforeComputingAZeroDelayAtTheMinimumBoundary() {
        var model = Model.assembler().discoverModels().addUnparsedModel("waiter.smithy", """
                $version: "2.0"
                namespace example
                use smithy.waiters#waitable
                service Example { version: "1", operations: [GetStatus] }
                @waitable(Ready: {
                    minDelay: 1,
                    maxDelay: 1,
                    acceptors: [{ state: "success", matcher: { success: true } }]
                })
                operation GetStatus { input: Input, output: Output }
                structure Input {}
                structure Output {}
                """).assemble().unwrap();
        var manifest = new MockManifest();
        var settings = GoSettings.from(buildMockPluginContext(model, manifest, "example#Example").getSettings());
        var symbols = GoCodegenPlugin.createSymbolProvider(model, settings);
        var writers = new GoDelegator(manifest, symbols);
        new Waiters2().writeAdditionalFiles(new GoCodegenContext(
                model, settings, symbols, manifest, writers, List.of()));
        writers.flushWriters();
        var operation = manifest.getFileString("api_op_GetStatus.go").orElseThrow();
        assertThat(operation, containsString("if remainingTime <= options.MinDelay || remainingTime <= 0 {"));
    }
}
