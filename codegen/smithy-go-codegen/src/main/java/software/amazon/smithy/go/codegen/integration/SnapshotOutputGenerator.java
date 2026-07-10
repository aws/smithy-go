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

package software.amazon.smithy.go.codegen.integration;

import java.util.List;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.StructureShape;

/**
 * Generates fully-populated Go struct literals for an operation's output shape, used as the expected value in a
 * response (deserialization) snapshot test. Delegates value writing to {@link SnapshotInputGenerator} so the
 * deterministic value rules (collection sizes, union variant selection, recursion handling) stay in one place.
 */
public final class SnapshotOutputGenerator {
    private final Model model;
    private final SnapshotInputGenerator values;

    public SnapshotOutputGenerator(Model model, SymbolProvider symbolProvider) {
        this.model = model;
        this.values = new SnapshotInputGenerator(model, symbolProvider);
    }

    /**
     * Generates test cases for the given operation's output shape.
     */
    public List<SnapshotInputGenerator.TestCase> generateCases(OperationShape operation) {
        var outputShape = model.expectShape(operation.getOutputShape(), StructureShape.class);
        return values.generateCasesForShape(outputShape);
    }

    /**
     * Generates test cases rooted at a modeled error structure shape, used as the expected value in a modeled-error
     * response (deserialization) snapshot test.
     */
    public List<SnapshotInputGenerator.TestCase> generateCasesForError(StructureShape errorShape) {
        return values.generateCasesForShape(errorShape);
    }
}
