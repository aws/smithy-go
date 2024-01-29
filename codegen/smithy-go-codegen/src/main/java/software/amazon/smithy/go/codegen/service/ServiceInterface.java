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

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoStdlibTypes;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.utils.MapUtils;

/**
 * Generates the interface that describes the service API.
 */
public final class ServiceInterface implements GoWriter.Writable {
    public static final String NAME = "Service";

    private final Model model;
    private final ServiceShape service;
    private final SymbolProvider symbolProvider;

    public ServiceInterface(Model model, ServiceShape service, SymbolProvider symbolProvider) {
        this.model = model;
        this.service = service;
        this.symbolProvider = symbolProvider;
    }

    @Override
    public void accept(GoWriter writer) {
        writer.write(generateInterface());
    }

    private GoWriter.Writable generateInterface() {
        return goTemplate("""
                type $L interface {
                    $W
                }
                """, NAME, generateOperations());
    }

    private GoWriter.Writable generateOperations() {
        return GoWriter.ChainWritable.of(
                TopDownIndex.of(model).getContainedOperations(service).stream()
                        .map(this::generateOperation)
                        .toList()
        ).compose(false);
    }

    private GoWriter.Writable generateOperation(OperationShape operation) {
        return goTemplate(
                "$operation:T($context:L, $input:P) ($output:P, error)",
                MapUtils.of(
                        "operation", symbolProvider.toSymbol(operation),
                        "context", GoStdlibTypes.Context.Context,
                        "input", symbolProvider.toSymbol(model.expectShape(operation.getInputShape())),
                        "output", symbolProvider.toSymbol(model.expectShape(operation.getOutputShape()))
                )
        );
    }
}
