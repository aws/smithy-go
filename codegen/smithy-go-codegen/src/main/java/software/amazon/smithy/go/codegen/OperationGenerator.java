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

package software.amazon.smithy.go.codegen;

import java.util.List;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.OperationIndex;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.StructureShape;

/**
 * Generates a client operation and associated custom shapes.
 */
final class OperationGenerator implements Runnable {

    private final GoSettings settings;
    private final Model model;
    private final SymbolProvider symbolProvider;
    private final GoWriter writer;
    private final ServiceShape service;
    private final OperationShape operation;
    private final Symbol operationSymbol;
    private final ApplicationProtocol applicationProtocol;
    private final ProtocolGenerator protocolGenerator;
    private final List<GoIntegration> integrations;

    OperationGenerator(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            ServiceShape service,
            OperationShape operation,
            Symbol operationSymbol,
            ApplicationProtocol applicationProtocol,
            ProtocolGenerator protocolGenerator,
            List<GoIntegration> integrations
    ) {
        this.settings = settings;
        this.model = model;
        this.symbolProvider = symbolProvider;
        this.writer = writer;
        this.service = service;
        this.operation = operation;
        this.operationSymbol = operationSymbol;
        this.applicationProtocol = applicationProtocol;
        this.protocolGenerator = protocolGenerator;
        this.integrations = integrations;
    }

    @Override
    public void run() {
        OperationIndex operationIndex = model.getKnowledge(OperationIndex.class);
        Symbol serviceSymbol = symbolProvider.toSymbol(service);

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

        writer.writeShapeDocs(operation);
        Symbol contextSymbol = SymbolUtils.createValueSymbolBuilder("Context", GoDependency.CONTEXT).build();
        writer.openBlock("func (c $P) $T(ctx $T, params $P, optFns ...func(*Options)) ($P, error) {", "}",
                serviceSymbol, operationSymbol, contextSymbol, inputSymbol, outputSymbol, () -> {
                    constructStack();

                    writer.write("options := c.options.Copy()");
                    writer.openBlock("for _, fn := range optFns {", "}", () -> {
                        writer.write("fn(&options)");
                    });
                    writer.openBlock("for _, fn := range options.APIOptions {", "}", () -> {
                        writer.write("if err := fn(stack); err != nil { return nil, err }");
                    });

                    // add middleware to operation stack
                    populateOperationMiddlewareStack();

                    constructHandler();
                    writer.write("result, metadata, err := handler.Handle(ctx, params)");
                    writer.write("if err != nil { return nil, err }");
                    writer.write("out := result.($P)", outputSymbol);
                    writer.write("out.ResultMetadata = metadata");
                    writer.write("return out, nil");
                }).write("");

        // Write out the input and output structures. These are written out here to prevent naming conflicts with other
        // shapes in the model.
        new StructureGenerator(model, symbolProvider, writer, inputShape, inputSymbol)
                .renderStructure(() -> { }, true);

        // The output structure gets a metadata member added.
        Symbol metadataSymbol = SymbolUtils.createValueSymbolBuilder(
                "Metadata", GoDependency.SMITHY_MIDDLEWARE).build();
        new StructureGenerator(model, symbolProvider, writer, outputShape, outputSymbol).renderStructure(() -> {
            if (outputShape.getMemberNames().size() != 0) {
                writer.write("");
            }
            writer.writeDocs("Metadata pertaining to the operation's result.");
            writer.write("ResultMetadata $T", metadataSymbol);
        });
    }

    private void constructStack() {
        if (!applicationProtocol.isHttpProtocol()) {
            throw new UnsupportedOperationException(
                    "Protocols other than HTTP are not yet implemented: " + applicationProtocol);
        }
        writer.addUseImports(GoDependency.SMITHY_MIDDLEWARE);
        writer.addUseImports(GoDependency.SMITHY_HTTP_TRANSPORT);
        writer.write("stack := middleware.NewStack($S, smithyhttp.NewStackRequest)", operationSymbol.getName());
    }

    private void constructHandler() {
        if (!applicationProtocol.isHttpProtocol()) {
            throw new UnsupportedOperationException(
                    "Protocols other than HTTP are not yet implemented: " + applicationProtocol);
        }
        Symbol decorateHandler = SymbolUtils.createValueSymbolBuilder(
                "DecorateHandler", GoDependency.SMITHY_MIDDLEWARE).build();
        Symbol newClientHandler = SymbolUtils.createValueSymbolBuilder(
                "NewClientHandler", GoDependency.SMITHY_HTTP_TRANSPORT).build();
        writer.write("handler := $T($T(options.HTTPClient), stack)", decorateHandler, newClientHandler);
    }


    /**
     * populateOperationMiddlewareStack adds middleware to the operation middleware stack.
     */
    private void populateOperationMiddlewareStack() {
        writer.write("");

        // protocol specific middleware generation
        if (protocolGenerator != null) {
            // add serializer middleware
            String serializerMiddlewareName = ProtocolGenerator.getSerializeMiddlewareName(operation.getId(),
                    protocolGenerator.getProtocolName());
            writer.write("stack.Serialize.Add(&$L{}, middleware.After)", serializerMiddlewareName);

            // add deserializer middleware
            String deserializerMiddlewareName = ProtocolGenerator.getDeserializeMiddlewareName(operation.getId(),
                    protocolGenerator.getProtocolName());
            writer.write("stack.Deserialize.Add(&$L{}, middleware.After)", deserializerMiddlewareName);
        }

        for (GoIntegration integration: integrations) {
            integration.assembleMiddlewareStack(settings, model, symbolProvider, writer, operation);
        }

        writer.write("");

    }

}
