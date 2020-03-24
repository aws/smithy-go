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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.SymbolDependency;

/**
 * Generates a go.mod file for the project.
 *
 * <p>See here for more information on the format: https://github.com/golang/go/wiki/Modules#gomod
 */
final class GoModGenerator {

    private GoModGenerator() {}

    static void writeGoMod(
            GoSettings settings,
            FileManifest manifest,
            Map<String, Map<String, SymbolDependency>> dependencies
    ) {
        Path goModFile = manifest.getBaseDir().resolve("go.mod");

        // `go mod init` will fail if the `go.mod` already exists, so this deletes
        //  it if it's present in the output. While it's technically possible
        //  to simply edit the file, it's easier to just start fresh.
        if (Files.exists(goModFile)) {
            try {
                Files.delete(goModFile);
            } catch (IOException e) {
                throw new CodegenException("Failed to delete existing go.mod file", e);
            }
        }
        CodegenUtils.runCommand("go mod init " + settings.getModuleName(), manifest.getBaseDir());

        Map<String, String> externalDependencies = getExternalDependencies(dependencies);
        for (Map.Entry<String, String> dependency : externalDependencies.entrySet()) {
            CodegenUtils.runCommand(
                    String.format("go mod edit -require=%s@%s", dependency.getKey(), dependency.getValue()),
                    manifest.getBaseDir());
        }
    }

    private static Map<String, String> getExternalDependencies(
            Map<String, Map<String, SymbolDependency>> dependencies
    ) {
        return dependencies.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("stdlib"))
                .flatMap(entry -> entry.getValue().entrySet().stream())
                .collect(Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().getVersion(), (a, b) -> b, TreeMap::new));
    }
}
