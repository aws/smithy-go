/*
 * Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.SymbolDependency;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.BooleanNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.node.StringNode;
import software.amazon.smithy.model.traits.UnstableTrait;
import software.amazon.smithy.utils.BuilderRef;
import software.amazon.smithy.utils.IoUtils;
import software.amazon.smithy.utils.SmithyBuilder;

/**
 * Generates a manifest description of the generated code, minimum go version,
 * and minimum dependencies required.
 */
public final class ManifestWriter {
    private static final String GENERATED_JSON = "generated.json";
    private static final String GENERATED_GO_MOD = "go.mod";
    private static final String DEFAULT_MINIMUM_GO_VERSION = "1.15";

    private final String moduleName;
    private final FileManifest fileManifest;
    private final List<SymbolDependency> dependencies;
    private final Optional<String> minimumGoVersion;
    private final boolean isUnstable;
    private final boolean isGoModule;

    private Map<String, String> minimumDependencies;

    private ManifestWriter(Builder builder) {
        moduleName = SmithyBuilder.requiredState("moduleName", builder.moduleName);
        fileManifest = SmithyBuilder.requiredState("fileManifest", builder.fileManifest);
        dependencies = builder.dependencies.copy();
        minimumGoVersion = builder.minimumGoVersion;
        isUnstable = builder.isUnstable;
        isGoModule = builder.isGoModule;
    }

    /**
     * Write the manifest description of the Smithy model based generated source code.
     *
     * @param settings     the go settings
     * @param model        the smithy model
     * @param fileManifest the file manifest
     * @param dependencies the list of symbol dependencies
     */
    public static void writeManifest(
            GoSettings settings,
            Model model,
            FileManifest fileManifest,
            List<SymbolDependency> dependencies
    ) {
        builder()
                .moduleName(settings.getModuleName())
                .fileManifest(fileManifest)
                .dependencies(dependencies)
                .isUnstable(settings.getService(model).getTrait(UnstableTrait.class).isPresent())
                .isGoModule(settings.getModuleVersion().isPresent())
                .build()
                .writeManifest();
    }


    /**
     * Write the manifest description of the generated code.
     */
    public void writeManifest() {
        Path manifestFile = fileManifest.getBaseDir().resolve(GENERATED_JSON);

        if (Files.exists(manifestFile)) {
            try {
                Files.delete(manifestFile);
            } catch (IOException e) {
                throw new CodegenException("Failed to delete existing " + GENERATED_JSON + " file", e);
            }
        }
        fileManifest.addFile(manifestFile);

        String minimumGoVersion = prepareDependencies();

        Node generatedJson = buildManifestFile(minimumGoVersion);
        fileManifest.writeFile(manifestFile.toString(), Node.prettyPrintJson(generatedJson) + "\n");

        if (isGoModule) {
            Path moduleFile = fileManifest.getBaseDir().resolve(GENERATED_GO_MOD);

            if (Files.exists(moduleFile)) {
                try {
                    Files.delete(moduleFile);
                } catch (IOException e) {
                    throw new CodegenException("Failed to delete existing " + GENERATED_GO_MOD + " file", e);
                }
            }
            fileManifest.addFile(moduleFile);

            String moduleContent = buildModuleFile(minimumGoVersion);
            fileManifest.writeFile(moduleFile.toString(), moduleContent);
        }

    }

    private String prepareDependencies() {
        List<SymbolDependency> nonStdLib = new ArrayList<>();
        Optional<String> minimumGoVersion = this.minimumGoVersion;

        for (SymbolDependency dependency : dependencies) {
            if (!dependency.getDependencyType().equals(GoDependency.Type.STANDARD_LIBRARY.toString())) {
                nonStdLib.add(dependency);
                continue;
            }

            var otherVersion = dependency.getVersion();
            if (minimumGoVersion.isPresent()) {
                if (minimumGoVersion.get().compareTo(otherVersion) < 0) {
                    minimumGoVersion = Optional.of(otherVersion);
                }
            } else {
                minimumGoVersion = Optional.of(otherVersion);
            }
        }
        minimumDependencies = gatherMinimumDependencies(nonStdLib.stream());
        return minimumGoVersion.orElse(DEFAULT_MINIMUM_GO_VERSION);
    }

    private Node buildManifestFile(String minimumGoVersion) {
        Map<StringNode, Node> manifestNodes = new HashMap<>();

        Map<StringNode, Node> dependencyNodes = new HashMap<>();
        for (Map.Entry<String, String> entry : minimumDependencies.entrySet()) {
            dependencyNodes.put(StringNode.from(entry.getKey()), StringNode.from(entry.getValue()));
        }

        Collection<String> generatedFiles = new ArrayList<>();
        Path baseDir = fileManifest.getBaseDir();
        for (Path filePath : fileManifest.getFiles()) {
            generatedFiles.add(baseDir.relativize(filePath).toString());
        }
        generatedFiles = generatedFiles.stream().sorted().collect(Collectors.toList());

        manifestNodes.put(StringNode.from("module"), StringNode.from(moduleName));
        manifestNodes.put(StringNode.from("go"), StringNode.from(minimumGoVersion));
        manifestNodes.put(StringNode.from("dependencies"), ObjectNode.objectNode(dependencyNodes));
        manifestNodes.put(StringNode.from("files"), ArrayNode.fromStrings(generatedFiles));
        manifestNodes.put(StringNode.from("unstable"), BooleanNode.from(isUnstable));

        return ObjectNode.objectNode(manifestNodes).withDeepSortedKeys();
    }

    private String buildModuleFile(String minimumGoVersion) {
        String template = IoUtils.readUtf8Resource(getClass(), "go.mod.template");
        List<String> dependencies = new ArrayList<>();

        for (Map.Entry<String, String> entry : minimumDependencies.entrySet()) {
            dependencies.add(String.format("%s %s", entry.getKey(), entry.getValue()));
        }

        return template
            .replace("${moduleName}", moduleName)
            .replace("${minimumGoVersion}", minimumGoVersion)
            .replace("${dependencies}", String.join("\n\t", dependencies));
    }

    private static Map<String, String> gatherMinimumDependencies(
            Stream<SymbolDependency> symbolStream
    ) {
        return SymbolDependency.gatherDependencies(symbolStream,
                GoDependency::mergeByMinimumVersionSelection).entrySet().stream().flatMap(
                entry -> entry.getValue().entrySet().stream()).collect(
                Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getVersion(), (a, b) -> b, TreeMap::new));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder implements SmithyBuilder<ManifestWriter> {
        private String moduleName;
        private FileManifest fileManifest;
        private final BuilderRef<List<SymbolDependency>> dependencies = BuilderRef.forList();
        private Optional<String> minimumGoVersion = Optional.empty();
        private boolean isUnstable;
        private boolean isGoModule;

        public Builder moduleName(String moduleName) {
            this.moduleName = moduleName;
            return this;
        }

        public Builder fileManifest(FileManifest fileManifest) {
            this.fileManifest = fileManifest;
            return this;
        }

        public Builder dependencies(List<SymbolDependency> dependencies) {
            this.dependencies.clear();
            this.dependencies.get().addAll(dependencies);
            return this;
        }

        public Builder minimumGoVersion(String minimumGoVersion) {
            this.minimumGoVersion = Optional.of(minimumGoVersion);
            return this;
        }

        public Builder isUnstable(boolean isUnstable) {
            this.isUnstable = isUnstable;
            return this;
        }

        public Builder isGoModule(boolean isGoModule) {
            this.isGoModule = isGoModule;
            return this;
        }

        @Override
        public ManifestWriter build() {
            return new ManifestWriter(this);
        }
    }
}
