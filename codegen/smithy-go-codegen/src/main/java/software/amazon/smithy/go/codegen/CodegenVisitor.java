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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.codegen.core.SymbolDependency;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.neighbor.Walker;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeVisitor;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.transform.ModelTransformer;

/**
 * Orchestrates Go client generation.
 */
final class CodegenVisitor extends ShapeVisitor.Default<Void> {

    private static final Logger LOGGER = Logger.getLogger(CodegenVisitor.class.getName());

    private final GoSettings settings;
    private final Model model;
    private final Model modelWithoutTraitShapes;
    private final ServiceShape service;
    private final FileManifest fileManifest;
    private final SymbolProvider symbolProvider;
    private final GoDelegator writers;
    private final List<GoIntegration> integrations = new ArrayList<>();

    CodegenVisitor(PluginContext context) {
        // Load all integrations.
        ClassLoader loader = context.getPluginClassLoader().orElse(getClass().getClassLoader());
        LOGGER.info("Attempting to discover GoIntegration from the classpath...");
        ServiceLoader.load(GoIntegration.class, loader)
                .forEach(integration -> {
                    LOGGER.info(() -> "Adding GoIntegration: " + integration.getClass().getName());
                    integrations.add(integration);
                });
        integrations.sort(Comparator.comparingInt(GoIntegration::getOrder));

        settings = GoSettings.from(context.getSettings());
        fileManifest = context.getFileManifest();

        Model resolvedModel = context.getModel();
        LOGGER.info(() -> "Preprocessing smithy model");
        for (GoIntegration goIntegration : integrations) {
            resolvedModel = goIntegration.preprocessModel(resolvedModel, settings);
        }

        // Add uniqueue operation input/output shapes
        resolvedModel = AddOperationShapes.execute(resolvedModel, settings.getService());

        model = resolvedModel;
        modelWithoutTraitShapes = ModelTransformer.create().getModelWithoutTraitShapes(model);

        service = settings.getService(model);
        LOGGER.info(() -> "Generating Go client for service " + service.getId());

        SymbolProvider resolvedProvider = GoCodegenPlugin.createSymbolProvider(model, settings.getModuleName());
        for (GoIntegration integration : integrations) {
            resolvedProvider = integration.decorateSymbolProvider(settings, model, resolvedProvider);
        }
        symbolProvider = resolvedProvider;

        writers = new GoDelegator(settings, model, fileManifest, symbolProvider);
    }

    void execute() {
        // Generate models that are connected to the service being generated.
        LOGGER.fine("Walking shapes from " + service.getId() + " to find shapes to generate");
        Set<Shape> serviceShapes = new TreeSet<>(new Walker(modelWithoutTraitShapes).walkShapes(service));

        for (Shape shape : serviceShapes) {
            shape.accept(this);
        }

        for (GoIntegration integration : integrations) {
            integration.writeAdditionalFiles(settings, model, symbolProvider, writers::useFileWriter);
        }

        LOGGER.fine("Flushing go writers");
        List<SymbolDependency> dependencies = writers.getDependencies();
        writers.flushWriters();

        LOGGER.fine("Running go fmt");
        CodegenUtils.runCommand("go fmt", fileManifest.getBaseDir());

        LOGGER.fine("Generating go.mod file");
        GoModGenerator.writeGoMod(settings, fileManifest, SymbolDependency.gatherDependencies(dependencies.stream()));
    }

    @Override
    protected Void getDefault(Shape shape) {
        return null;
    }

    @Override
    public Void structureShape(StructureShape shape) {
        writers.useShapeWriter(shape, writer -> new StructureGenerator(model, symbolProvider, writer, shape).run());
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
        writers.useShapeWriter(shape, writer -> new UnionGenerator(model, symbolProvider, writer, shape).run());
        return null;
    }

    @Override
    public Void serviceShape(ServiceShape shape) {
        // TODO: implement client generation
        writers.useShapeWriter(shape, writer -> {
        });
        return null;
    }
}
