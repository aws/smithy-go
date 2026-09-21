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
import software.amazon.smithy.go.codegen.endpoints.EndpointMiddlewareGenerator;
import software.amazon.smithy.build.MockManifest;
import software.amazon.smithy.model.Model;

public class EndpointPathTest {
    @Test
    public void preservesEscapedPathsBeforeUpdatingDecodedPath() {
        var model = Model.assembler().discoverModels().addUnparsedModel("endpoint.smithy", """
                $version: "2.0"
                namespace example
                service Example { version: "1" }
                """).assemble().unwrap();
        var manifest = new MockManifest();
        var settings = GoSettings.from(buildMockPluginContext(model, manifest, "example#Example").getSettings());
        var symbols = GoCodegenPlugin.createSymbolProvider(model, settings);
        var writers = new GoDelegator(manifest, symbols);
        var writer = new GoWriter("example");
        new EndpointMiddlewareGenerator(new GoCodegenContext(
                model, settings, symbols, manifest, writers, List.of())).generate().accept(writer);
        var generated = writer.toString();
        System.out.println(generated);
        assertThat(generated, containsString("if endpt.URI.RawPath != \"\" || req.URL.RawPath != \"\" {"));
        assertThat(generated, containsString(
                "req.URL.RawPath = smithyhttp.JoinPath(endpt.URI.EscapedPath(), req.URL.EscapedPath())"));
        org.junit.jupiter.api.Assertions.assertTrue(
                generated.indexOf("req.URL.RawPath =") < generated.indexOf("req.URL.Path ="));
    }
}