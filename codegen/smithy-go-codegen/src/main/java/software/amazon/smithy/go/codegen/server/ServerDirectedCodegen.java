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

package software.amazon.smithy.go.codegen.server;

import static java.util.stream.Collectors.toSet;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.server.ServerCodegenUtil.getShapesToSerde;
import static software.amazon.smithy.go.codegen.server.ServerCodegenUtil.isUnit;
import static software.amazon.smithy.go.codegen.server.ServerCodegenUtil.withUnit;

import java.util.List;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.SymbolDependency;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.codegen.core.WriterDelegator;
import software.amazon.smithy.codegen.core.directed.CreateContextDirective;
import software.amazon.smithy.codegen.core.directed.CreateSymbolProviderDirective;
import software.amazon.smithy.codegen.core.directed.DirectedCodegen;
import software.amazon.smithy.codegen.core.directed.GenerateEnumDirective;
import software.amazon.smithy.codegen.core.directed.GenerateErrorDirective;
import software.amazon.smithy.codegen.core.directed.GenerateIntEnumDirective;
import software.amazon.smithy.codegen.core.directed.GenerateOperationDirective;
import software.amazon.smithy.codegen.core.directed.GenerateServiceDirective;
import software.amazon.smithy.codegen.core.directed.GenerateStructureDirective;
import software.amazon.smithy.codegen.core.directed.GenerateUnionDirective;
import software.amazon.smithy.go.codegen.CodegenUtils;
import software.amazon.smithy.go.codegen.EnumGenerator;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoModGenerator;
import software.amazon.smithy.go.codegen.GoModuleInfo;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.IntEnumGenerator;
import software.amazon.smithy.go.codegen.ManifestWriter;
import software.amazon.smithy.go.codegen.SmithyGoTypes;
import software.amazon.smithy.go.codegen.StructureGenerator;
import software.amazon.smithy.go.codegen.SymbolVisitor;
import software.amazon.smithy.go.codegen.UnionGenerator;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.model.knowledge.TopDownIndex;
import software.amazon.smithy.model.shapes.EnumShape;
import software.amazon.smithy.model.shapes.IntEnumShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.utils.SmithyInternalApi;

@SmithyInternalApi
public class ServerDirectedCodegen implements DirectedCodegen<GoCodegenContext, GoSettings, GoIntegration> {
    @Override
    public SymbolProvider createSymbolProvider(CreateSymbolProviderDirective<GoSettings> directive) {
        return new SymbolVisitor(withUnit(directive.model()), directive.settings());
    }

    @Override
    public GoCodegenContext createContext(CreateContextDirective<GoSettings, GoIntegration> directive) {
        return new GoCodegenContext(
                withUnit(directive.model()),
                directive.settings(),
                directive.symbolProvider(),
                directive.fileManifest(),
                new WriterDelegator<>(directive.fileManifest(), directive.symbolProvider(),
                        (filename, namespace) -> new GoWriter(namespace)),
                directive.integrations()
        );
    }

    @Override
    public void generateService(GenerateServiceDirective<GoCodegenContext, GoSettings> directive) {
        var namespace = directive.settings().getModuleName();
        var delegator = directive.context().writerDelegator();
        var settings = directive.settings();

        var protocolGenerator = resolveProtocolGenerator(directive.context());

        var model = directive.model();
        var service = directive.service();
        var shapesToDeserialize = TopDownIndex.of(model).getContainedOperations(service).stream()
                .map(it -> model.expectShape(it.getInputShape(), StructureShape.class))
                .flatMap(it -> getShapesToSerde(model, it).stream())
                .collect(toSet());
        var shapesToSerialize = TopDownIndex.of(model).getContainedOperations(service).stream()
                .map(it -> model.expectShape(it.getOutputShape(), StructureShape.class))
                .flatMap(it -> getShapesToSerde(model, it).stream())
                .collect(toSet());

        delegator.useFileWriter("service.go", namespace, GoWriter.ChainWritable.of(
                new NotImplementedError(),
                new ServerInterface(directive.model(), directive.service(), directive.symbolProvider()),
                new NoopServiceStruct(directive.model(), directive.service(), directive.symbolProvider()),
                new RequestHandler(protocolGenerator)
        ).compose());

        delegator.useFileWriter("options.go", namespace,
                new OptionsStruct(protocolGenerator));
        delegator.useFileWriter("deserialize.go", namespace,
                protocolGenerator.generateDeserializers(shapesToDeserialize));
        delegator.useFileWriter("serialize.go", namespace,
                protocolGenerator.generateSerializers(shapesToSerialize));
        delegator.useFileWriter("validate.go", namespace,
                new ServerValidationgenerator().generate(model, service, directive.symbolProvider()));
        delegator.useFileWriter("protocol.go", namespace,
                protocolGenerator.generateProtocolSource());

        var noDocSerde = goTemplate("type noSmithyDocumentSerde = $T", SmithyGoTypes.Smithy.Document.NoSerde);
        delegator.useFileWriter("document.go", namespace, noDocSerde);
        delegator.useFileWriter("types/document.go", "types", noDocSerde);

        List<SymbolDependency> dependencies = delegator.getDependencies();
        delegator.flushWriters();

        GoModuleInfo goModuleInfo = new GoModuleInfo.Builder()
                .goDirective(settings.getGoDirective())
                .dependencies(dependencies)
                .build();
        GoModGenerator.writeGoMod(settings, directive.fileManifest(), goModuleInfo);
        ManifestWriter.writeManifest(settings, model, directive.fileManifest(), goModuleInfo);
    }

    @Override
    public void generateOperation(GenerateOperationDirective<GoCodegenContext, GoSettings> directive) {
        var protocolGenerator = resolveProtocolGenerator(directive.context());
        directive.context().writerDelegator().useShapeWriter(directive.shape(),
                protocolGenerator.generateHandleOperation(directive.shape()));
    }

    @Override
    public void generateStructure(GenerateStructureDirective<GoCodegenContext, GoSettings> directive) {
        // FUTURE is this necessary? it was taken from the client routine
        if (directive.shape().getId().getNamespace().equals(CodegenUtils.getSyntheticTypeNamespace())) {
            return;
        }
        if (isUnit(directive.shape().getId())) {
            return;
        }

        var delegator = directive.context().writerDelegator();
        delegator.useShapeWriter(directive.shape(), writer ->
                new StructureGenerator(
                        directive.model(),
                        directive.symbolProvider(),
                        writer,
                        directive.service(),
                        directive.shape(),
                        directive.symbolProvider().toSymbol(directive.shape()),
                        null
                ).run()
        );
    }

    @Override
    public void generateError(GenerateErrorDirective<GoCodegenContext, GoSettings> directive) {
        var delegator = directive.context().writerDelegator();
        delegator.useShapeWriter(directive.shape(), writer ->
                new StructureGenerator(
                        directive.model(),
                        directive.symbolProvider(),
                        writer,
                        directive.service(),
                        directive.shape(),
                        directive.symbolProvider().toSymbol(directive.shape()),
                        null
                ).run()
        );
    }

    @Override
    public void generateUnion(GenerateUnionDirective<GoCodegenContext, GoSettings> directive) {
        var delegator = directive.context().writerDelegator();
        delegator.useShapeWriter(directive.shape(), writer ->
                new UnionGenerator(directive.model(), directive.symbolProvider(), directive.shape())
                        .generateUnion(writer)
        );
    }

    @Override
    public void generateEnumShape(GenerateEnumDirective<GoCodegenContext, GoSettings> directive) {
        var delegator = directive.context().writerDelegator();
        delegator.useShapeWriter(directive.shape(), writer ->
                new EnumGenerator(directive.symbolProvider(), writer, (EnumShape) directive.shape()).run()
        );
    }

    @Override
    public void generateIntEnumShape(GenerateIntEnumDirective<GoCodegenContext, GoSettings> directive) {
        directive.context().writerDelegator().useShapeWriter(directive.shape(), writer ->
                new IntEnumGenerator(directive.symbolProvider(), writer, (IntEnumShape) directive.shape()).run()
        );
    }

    private ServerProtocolGenerator resolveProtocolGenerator(GoCodegenContext ctx) {
        var model = ctx.model();
        var service = ctx.settings().getService(model);

        var protocolGenerators = ctx.integrations().stream()
                .flatMap(it -> it.getServerProtocolGenerators(ctx).stream())
                .filter(it -> service.hasTrait(it.getProtocol()))
                .toList();
        if (protocolGenerators.isEmpty()) {
            throw new CodegenException("could not resolve protocol generator");
        }

        return protocolGenerators.get(0);
    }
}
