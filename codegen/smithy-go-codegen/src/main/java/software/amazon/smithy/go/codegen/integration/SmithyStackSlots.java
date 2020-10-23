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
import software.amazon.smithy.go.codegen.MiddlewareIdentifier;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.utils.ListUtils;

public class SmithyStackSlots implements GoIntegration {
    @Override
    public byte getOrder() {
        return -127;
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return ListUtils.of(RuntimeClientPlugin.builder()
                .registerStackSlots(StackSlotRegistrar.builder()
                        .addInitializeSlotMutator(addAfter(ListUtils.of(
                                symbolId("OperationIdempotencyTokenAutoFill"),
                                symbolId("OperationInputValidation")
                        )))
                        .addSerializeSlotMutator(addAfter(ListUtils.of(
                                symbolId("OperationSerializer")
                        )))
                        .addBuildSlotMutator(addAfter(ListUtils.of(
                                symbolId("ContentChecksum"),
                                symbolId("ComputeContentLength"),
                                symbolId("ValidateContentLength")
                        )))
                        .addDeserializeSlotMutators(addAfter(ListUtils.of(
                                symbolId("ErrorCloseResponseBody"),
                                symbolId("CloseResponseBody"),
                                symbolId("OperationDeserializer")
                        )))
                        .build())
                .build());
    }

    private MiddlewareIdentifier symbolId(String name) {
        return MiddlewareIdentifier.symbol(
                SymbolUtils.createValueSymbolBuilder(name, SmithyGoDependency.SMITHY_MIDDLEWARE_ID)
                        .build());
    }

    private StackSlotRegistrar.SlotMutator addAfter(List<MiddlewareIdentifier> identifiers) {
        return StackSlotRegistrar.SlotMutator.addAfter().identifiers(identifiers).build();
    }
}
