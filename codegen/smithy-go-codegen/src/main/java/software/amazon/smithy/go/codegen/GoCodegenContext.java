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

import static java.util.stream.Collectors.toSet;

import java.util.HashSet;
import java.util.List;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.codegen.core.CodegenContext;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.WriterDelegator;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.neighbor.Walker;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public record GoCodegenContext(
        Model model,
        GoSettings settings,
        SymbolProvider symbolProvider,
        FileManifest fileManifest,
        WriterDelegator<GoWriter> writerDelegator,
        List<GoIntegration> integrations
) implements CodegenContext<GoSettings, GoWriter, GoIntegration> {
    public ServiceShape service() {
        return settings.getService(model);
    }

    /**
     * Return all **non-member** shapes in the (de)serialization tree of the service. That is, every shape in the tree
     * of the input/outputs of all operations, plus all structures with @error.
     */
    public List<Shape> serdeShapes() {
        var walker = new Walker(model);
        var operations = TopDownIndex.of(model).getContainedOperations(service());

        var shapes = new HashSet<Shape>();
        shapes.addAll(operations.stream()
                .map(it -> model.expectShape(it.getInputShape()))
                .flatMap(it -> walker.walkShapes(it).stream())
                .collect(toSet()));
        shapes.addAll(operations.stream()
                .map(it -> model.expectShape(it.getOutputShape()))
                .flatMap(it -> walker.walkShapes(it).stream())
                .collect(toSet()));
        shapes.addAll(model.getStructureShapesWithTrait(ErrorTrait.class).stream()
                .flatMap(it -> walker.walkShapes(it).stream())
                .collect(toSet()));

        return shapes.stream()
                .sorted()
                .filter(it -> it.getType() != ShapeType.MEMBER)
                .toList();
    }

    public <T extends Shape> List<T> serdeShapes(Class<T> clazz) {
        return serdeShapes().stream()
                .filter(clazz::isInstance)
                .map(clazz::cast)
                .toList();
    }
}
