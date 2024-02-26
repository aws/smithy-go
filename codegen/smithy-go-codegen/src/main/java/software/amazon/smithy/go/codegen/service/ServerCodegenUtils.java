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

import java.util.stream.Stream;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public final class ServerCodegenUtils {
    private ServerCodegenUtils() {}

    public static boolean operationHasEventStream(
        Model model,
        StructureShape inputShape,
        StructureShape outputShape
    ) {
        return Stream
            .concat(
                inputShape.members().stream(),
                outputShape.members().stream())
            .anyMatch(memberShape -> StreamingTrait.isEventStream(model, memberShape));
    }
}
