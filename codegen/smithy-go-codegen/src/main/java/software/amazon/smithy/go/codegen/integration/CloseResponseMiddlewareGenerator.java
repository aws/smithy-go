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
 *
 *
 */

package software.amazon.smithy.go.codegen.integration;

import java.util.List;
import java.util.Optional;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.knowledge.HttpBindingIndex;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.utils.ListUtils;

public class CloseResponseMiddlewareGenerator implements GoIntegration {
    /**
     * Gets the sort order of the customization from -128 to 127, with lowest
     * executed first.
     *
     * @return Returns the sort order, defaults to -40.
     */
    @Override
    public byte getOrder() {
        return 127;
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return ListUtils.of(
                // Add deserialization middleware to close the response in case of errors.
                RuntimeClientPlugin.builder()
                        .servicePredicate((model, service) -> {
                            // TODO is HTTP based protocol
                            return true;
                        })
                        .registerMiddleware(MiddlewareRegistrar.builder()
                                .resolvedFunction(SymbolUtils.createValueSymbolBuilder(
                                        "AddErrorCloseResponseBodyMiddleware", SmithyGoDependency.SMITHY_HTTP_TRANSPORT)
                                        .build())
                                .build()
                        )
                        .build(),

                // Add deserialization middleware to close the response for non-output-streaming operations.
                RuntimeClientPlugin.builder()
                        .servicePredicate((model, service) -> {
                            // TODO is HTTP based protocol
                            return true;
                        })
                        .operationPredicate((model, service, operation) -> {
                            // TODO operation output NOT event stream

                            // Don't auto close response body when response is streaming.
                            HttpBindingIndex httpBindingIndex = model.getKnowledge(HttpBindingIndex.class);
                            Optional<HttpBinding> payloadBinding = httpBindingIndex.getResponseBindings(operation,
                                    HttpBinding.Location.PAYLOAD).stream().findFirst();
                            if (payloadBinding.isPresent()) {
                                MemberShape memberShape = payloadBinding.get().getMember();
                                Shape payloadShape = model.expectShape(memberShape.getTarget());

                                return !payloadShape.hasTrait(StreamingTrait.class);
                            }

                            return true;
                        })
                        .registerMiddleware(MiddlewareRegistrar.builder()
                                .resolvedFunction(SymbolUtils.createValueSymbolBuilder(
                                        "AddCloseResponseBodyMiddleware",
                                        SmithyGoDependency.SMITHY_HTTP_TRANSPORT)
                                        .build())
                                .build()
                        )
                        .build()
        );
    }
}
