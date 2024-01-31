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

import static java.util.stream.Collectors.toSet;
import static software.amazon.smithy.go.codegen.service.Util.getShapesToSerde;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolDependency;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.AddOperationShapes;
import software.amazon.smithy.go.codegen.CodegenUtils;
import software.amazon.smithy.go.codegen.EnumGenerator;
import software.amazon.smithy.go.codegen.GoDelegator;
import software.amazon.smithy.go.codegen.GoModGenerator;
import software.amazon.smithy.go.codegen.GoModuleInfo;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoSettings.ArtifactType;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.IntEnumGenerator;
import software.amazon.smithy.go.codegen.ManifestWriter;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.ServiceIndex;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.neighbor.Walker;
import software.amazon.smithy.model.shapes.IntEnumShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.transform.ModelTransformer;
import software.amazon.smithy.utils.OptionalUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Orchestrates Go client generation.
 */
@SmithyInternalApi
final class ServerCodegenVisitor extends ShapeVisitor.Default<Void> {

    private static final Logger LOGGER = Logger.getLogger(ServerCodegenVisitor.class.getName());

    private final GoSettings settings;
    private final Model model;
    private final Model modelWithoutTraitShapes;
    private final ServiceShape service;
    private final FileManifest fileManifest;
    private final SymbolProvider symbolProvider;
    private final GoDelegator writers;
    private final List<GoServerIntegration> integrations = new ArrayList<>();
    private final ServerProtocolGenerator protocolGenerator;

    ServerCodegenVisitor(PluginContext context) {
        // Load all integrations.
        ClassLoader loader = context.getPluginClassLoader().orElse(getClass().getClassLoader());
        LOGGER.info("Attempting to discover GoServerIntegration from the classpath...");
        ServiceLoader.load(GoIntegration.class, loader)
                .forEach(integration -> {
                    if (integration.getArtifactType().equals(ArtifactType.SERVER)) {
                        LOGGER.info(() -> "Adding GoIntegration: " + integration.getClass().getName());
                        integrations.add((GoServerIntegration) integration);
                    }
                });
        integrations.sort(Comparator.comparingInt(GoServerIntegration::getOrder));

        settings = GoSettings.from(context.getSettings());
        fileManifest = context.getFileManifest();

        Model resolvedModel = context.getModel();

        var modelTransformer = ModelTransformer.create();

        /*
         * smithy 1.23.0 added support for mixins. This transform flattens and applies
         * the mixins
         * and remove them from the model
         */
        resolvedModel = modelTransformer.flattenAndRemoveMixins(resolvedModel);

        // Add unique operation input/output shapes
        resolvedModel = AddOperationShapes.execute(resolvedModel, settings.getService());

        /*
         * smithy 1.12.0 added support for binding common errors to the service shape
         * this transform copies these common errors to the operations
         */
        resolvedModel = modelTransformer.copyServiceErrorsToOperations(resolvedModel,
                settings.getService(resolvedModel));

        LOGGER.info(() -> "Preprocessing smithy model");
        for (GoServerIntegration goIntegration : integrations) {
            resolvedModel = goIntegration.preprocessModel(resolvedModel, settings);
        }

        model = resolvedModel;

        // process final model
        integrations.forEach(integration -> {
            integration.processFinalizedModel(settings, model);
        });

        modelWithoutTraitShapes = modelTransformer.getModelWithoutTraitShapes(model);

        service = settings.getService(model);
        LOGGER.info(() -> "Generating Go server for service " + service.getId());

        SymbolProvider resolvedProvider = GoServerCodegenPlugin.createSymbolProvider(model, settings);
        for (GoServerIntegration integration : integrations) {
            resolvedProvider = integration.decorateSymbolProvider(settings, model, resolvedProvider);
        }
        symbolProvider = resolvedProvider;

        protocolGenerator = resolveProtocolGenerator(integrations, model, service, settings, symbolProvider);

        writers = new GoDelegator(fileManifest, symbolProvider);
    }

    private static ServerProtocolGenerator resolveProtocolGenerator(
            Collection<GoServerIntegration> integrations,
            Model model,
            ServiceShape service,
            GoSettings settings,
            SymbolProvider symbolProvider
    ) {
        // Collect all the supported protocol generators.
        Map<ShapeId, ServerProtocolGenerator> generators = new HashMap<>();
        for (GoServerIntegration integration : integrations) {
            List<ServerProtocolGenerator> protocolGenerators =
                integration.getServerProtocolGenerators(model, service, symbolProvider);
            for (ServerProtocolGenerator generator : protocolGenerators) {
                generators.put(generator.getProtocol(), generator);
            }
        }

        ServiceIndex serviceIndex = ServiceIndex.of(model);

        ShapeId protocolTrait = settings.resolveServiceProtocol(serviceIndex, service, generators.keySet());
        settings.setProtocol(protocolTrait);
        return generators.get(protocolTrait);
    }

    void execute() {
        // Generate models that are connected to the service being generated.
        LOGGER.fine("Walking shapes from " + service.getId() + " to find shapes to generate");
        Set<Shape> serviceShapes = new TreeSet<>(new Walker(modelWithoutTraitShapes).walkShapes(service));

        for (Shape shape : serviceShapes) {
            shape.accept(this);
        }

        // Generate a struct to handle unknown tags in unions
        List<UnionShape> unions = serviceShapes.stream()
                .map(Shape::asUnionShape)
                .flatMap(OptionalUtils::stream)
                .collect(Collectors.toList());
        if (!unions.isEmpty()) {
            writers.useShapeWriter(unions.get(0), writer -> {
                ServerUnionGenerator.generateUnknownUnion(writer, unions, symbolProvider);
            });
        }

        for (GoServerIntegration integration : integrations) {
            integration.writeAdditionalFiles(settings, model, symbolProvider, writers::useFileWriter);
            integration.writeAdditionalFiles(settings, model, symbolProvider, writers);
        }

        LOGGER.info("Generating serde for protocol " + protocolGenerator.getProtocol() + " on " + service.getId());

        var shapesToDeserialize = TopDownIndex.of(model).getContainedOperations(service).stream()
                .map(it -> model.expectShape(it.getInputShape(), StructureShape.class))
                .flatMap(it -> getShapesToSerde(model, it).stream())
                .collect(toSet());
        var shapesToSerialize = TopDownIndex.of(model).getContainedOperations(service).stream()
                .map(it -> model.expectShape(it.getOutputShape(), StructureShape.class))
                .flatMap(it -> getShapesToSerde(model, it).stream())
                .collect(toSet());

        writers.useFileWriter("service.go", settings.getModuleName(), GoWriter.ChainWritable.of(
                new NotImplementedError(),
                new ServerInterface(model, service, symbolProvider),
                new NoopServiceStruct(model, service, symbolProvider),
                new RequestHandler(protocolGenerator)
        ).compose());
        writers.useFileWriter("options.go", settings.getModuleName(),
                new OptionsStruct(protocolGenerator));
        writers.useFileWriter("deserialize.go", settings.getModuleName(),
                protocolGenerator.generateDeserializers(shapesToDeserialize));
        writers.useFileWriter("serialize.go", settings.getModuleName(),
                protocolGenerator.generateSerializers(shapesToSerialize));

        LOGGER.fine("Flushing go writers");
        List<SymbolDependency> dependencies = writers.getDependencies();
        writers.flushWriters();

        GoModuleInfo goModuleInfo = new GoModuleInfo.Builder()
                .goDirective(settings.getGoDirective())
                .dependencies(dependencies)
                .build();

        GoModGenerator.writeGoMod(settings, fileManifest, goModuleInfo);

        LOGGER.fine("Generating build manifest file");
        ManifestWriter.writeManifest(settings, model, fileManifest, goModuleInfo);
    }

    @Override
    protected Void getDefault(Shape shape) {
        return null;
    }

    @Override
    public Void structureShape(StructureShape shape) {
        if (shape.getId().getNamespace().equals(CodegenUtils.getSyntheticTypeNamespace())) {
            return null;
        }
        Symbol symbol = symbolProvider.toSymbol(shape);
        writers.useShapeWriter(shape, writer -> new ServerStructureGenerator(
                model, symbolProvider, writer, service, shape, symbol, null).run());
        return null;
    }

    @Override
    public Void stringShape(StringShape shape) {
        if (shape.hasTrait(EnumTrait.class)) {
            writers.useShapeWriter(shape, writer -> new EnumGenerator(symbolProvider, writer, shape).run());
        }
        return null;
    }

    @Override
    public Void unionShape(UnionShape shape) {
        ServerUnionGenerator generator = new ServerUnionGenerator(model, symbolProvider, shape);
        writers.useShapeWriter(shape, generator::generateUnion);
        writers.useShapeExportedTestWriter(shape, generator::generateUnionExamples);
        return null;
    }

    @Override
    public Void serviceShape(ServiceShape shape) {
        if (!Objects.equals(service, shape)) {
            LOGGER.fine(() -> "Skipping `" + shape.getId() + "` because it is not `" + service.getId() + "`");
            return null;
        }

        // Write API server's package doc for the service.
        writers.useFileWriter("doc.go", settings.getModuleName(), (writer) -> {
            writer.writePackageDocs(String.format(
                    "Package %s provides the API server, operations, and parameter types for %s.",
                    CodegenUtils.getDefaultPackageImportName(settings.getModuleName()),
                    CodegenUtils.getServiceTitle(shape, "the API")));
            writer.writePackageDocs("");
            writer.writePackageShapeDocs(shape);
        });

        // Write API server types and utilities.
        TopDownIndex topDownIndex = TopDownIndex.of(model);
        Set<OperationShape> containedOperations = new TreeSet<>(topDownIndex.getContainedOperations(service));
        for (OperationShape operation : containedOperations) {
            writers.useShapeWriter(operation, operationWriter -> {
                new ServerOperationGenerator(
                    model,
                    symbolProvider,
                    operationWriter,
                    service,
                    operation
                ).run();
            });
        }
        return null;
    }

    @Override
    public Void intEnumShape(IntEnumShape shape) {
        writers.useShapeWriter(shape, writer -> new IntEnumGenerator(symbolProvider, writer, shape).run());
        return null;
    }
}
