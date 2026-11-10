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
import static org.hamcrest.Matchers.not;
import static software.amazon.smithy.go.codegen.TestUtils.getSettingsNode;
import static software.amazon.smithy.go.codegen.TestUtils.loadSmithyModelFromResource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.smithy.build.MockManifest;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;

public class RetryableTraitGeneratorTest {
    private static MockManifest generate(boolean useLegacySerde) {
        Model model = loadSmithyModelFromResource("retryable-trait-test");
        MockManifest manifest = new MockManifest();
        PluginContext context = PluginContext.builder()
                .model(model)
                .fileManifest(manifest)
                .settings(getSettingsNode("smithy.example#Example", "example", "0.0.1", false, "Example")
                        .withMember("useLegacySerde", Node.from(useLegacySerde)))
                .build();

        new GoCodegenPlugin().execute(context);
        return manifest;
    }

    private static String errors(boolean useLegacySerde) {
        return generate(useLegacySerde).getFileString("types/errors.go").get();
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    public void rendersRetryableTraitForRetryableTrait(boolean useLegacySerde) {
        assertThat(errors(useLegacySerde), containsString(
                "func (e *RetryableConflict) RetryableTrait() (smithytraits.Retryable, bool) {"
                        + " return smithytraits.Retryable{Throttling: false}, true }"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    public void rendersThrottlingForThrottlingRetryableTrait(boolean useLegacySerde) {
        assertThat(errors(useLegacySerde), containsString(
                "func (e *ThrottledRequest) RetryableTrait() (smithytraits.Retryable, bool) {"
                        + " return smithytraits.Retryable{Throttling: true}, true }"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    public void rendersAbsentRetryableTraitWithoutRetryableTrait(boolean useLegacySerde) {
        assertThat(errors(useLegacySerde), containsString(
                "func (e *NotRetryable) RetryableTrait() (smithytraits.Retryable, bool) {"
                        + " return smithytraits.Retryable{}, false }"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    public void importsSmithyTraits(boolean useLegacySerde) {
        assertThat(errors(useLegacySerde), containsString("smithytraits \"github.com/aws/smithy-go/traits\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    public void escapesErrorMemberNamedRetryableTrait(boolean useLegacySerde) {
        assertThat(errors(useLegacySerde), containsString("RetryableTrait_ *string"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    public void doesNotEscapeNonErrorMemberNamedRetryableTrait(boolean useLegacySerde) {
        String operation = generate(useLegacySerde).getFileString("api_op_GetThing.go").get();
        assertThat(operation, containsString("RetryableTrait *string"));
        assertThat(operation, not(containsString("RetryableTrait_")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    public void rendersNoLegacyRetryHintsOrSchemaAccessor(boolean useLegacySerde) {
        String errors = errors(useLegacySerde);
        assertThat(errors, not(containsString("RetryableError()")));
        assertThat(errors, not(containsString("ThrottlingError()")));
        assertThat(errors, not(containsString("SmithySchema()")));
    }

    @Test
    public void omitsRetryableTraitFromSchemas() {
        String schemas = generate(false).getFileString("schemas/schemas.go").get();
        assertThat(schemas, not(containsString("smithytraits.Retryable")));
    }
}
