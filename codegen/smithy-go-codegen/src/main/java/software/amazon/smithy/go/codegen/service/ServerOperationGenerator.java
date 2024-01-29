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
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.EventStreamGenerator;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.StructureGenerator;
import software.amazon.smithy.go.codegen.SymbolUtils;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.DeprecatedTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Generates a client operation and associated custom shapes.
 */
@SmithyInternalApi
public final class ServerOperationGenerator implements Runnable {

    private final Model model;
    private final SymbolProvider symbolProvider;
    private final GoWriter writer;
    private final ServiceShape service;
    private final OperationShape operation;

    public ServerOperationGenerator(
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            ServiceShape service,
            OperationShape operation
    ) {
        this.model = model;
        this.symbolProvider = symbolProvider;
        this.writer = writer;
        this.service = service;
        this.operation = operation;
    }

    @Override
    public void run() {
        OperationIndex operationIndex = OperationIndex.of(model);

        if (!operationIndex.getInput(operation).isPresent()) {
            // Theoretically this shouldn't ever get hit since we automatically insert synthetic inputs / outputs.
            throw new CodegenException(
                    "Operations are required to have input shapes in order to allow for future evolution.");
        }
        StructureShape inputShape = operationIndex.getInput(operation).get();
        Symbol inputSymbol = symbolProvider.toSymbol(inputShape);

        if (!operationIndex.getOutput(operation).isPresent()) {
            throw new CodegenException(
                    "Operations are required to have output shapes in order to allow for future evolution.");
        }
        StructureShape outputShape = operationIndex.getOutput(operation).get();
        Symbol outputSymbol = symbolProvider.toSymbol(outputShape);

        // Generate operation method
        final boolean hasDocs = writer.writeShapeDocs(operation);
        operation.getTrait(DeprecatedTrait.class)
                .ifPresent(trait -> {
                    if (hasDocs) {
                        writer.writeDocs("");
                    }
                    final String defaultMessage = "This operation has been deprecated.";
                    writer.writeDocs("Deprecated: " + trait.getMessage().map(s -> {
                        if (s.length() == 0) {
                            return defaultMessage;
                        }
                        return s;
                    }).orElse(defaultMessage));
                });
        // Write out the input and output structures. These are written out here to prevent naming conflicts with other
        // shapes in the model.
        new StructureGenerator(model, symbolProvider, writer, service, inputShape, inputSymbol, null)
                .renderStructure(() -> { }, true);

        // The output structure gets a metadata member added.
        Symbol metadataSymbol = SymbolUtils.createValueSymbolBuilder("Metadata", SmithyGoDependency.SMITHY_MIDDLEWARE)
                .build();

        boolean hasEventStream = Stream.concat(inputShape.members().stream(),
                        outputShape.members().stream())
                .anyMatch(memberShape -> StreamingTrait.isEventStream(model, memberShape));

        new StructureGenerator(model, symbolProvider, writer, service, outputShape, outputSymbol, null)
                .renderStructure(() -> {
                    if (outputShape.getMemberNames().size() != 0) {
                        writer.write("");
                    }

                    if (hasEventStream) {
                        writer.write("eventStream $P",
                                        EventStreamGenerator.getEventStreamOperationStructureSymbol(service, operation))
                                .write("");
                    }

                    writer.writeDocs("Metadata pertaining to the operation's result.");
                    writer.write("ResultMetadata $T", metadataSymbol);
                });

        if (hasEventStream) {
            writer.write("""
                         // GetStream returns the type to interact with the event stream.
                         func (o $P) GetStream() $P {
                             return o.eventStream
                         }
                         """, outputSymbol, EventStreamGenerator.getEventStreamOperationStructureSymbol(
                    service, operation));
        }
    }
}
