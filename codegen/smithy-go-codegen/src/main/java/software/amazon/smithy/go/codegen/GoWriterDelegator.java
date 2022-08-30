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

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.codegen.core.SymbolDependency;

public class GoWriterDelegator {
    private final FileManifest fileManifest;
    private final Map<String, GoWriter> writers = new HashMap<>();

    public GoWriterDelegator(FileManifest fileManifest) {
        this.fileManifest = fileManifest;
    }

    /**
     * Writes all pending writers to disk and then clears them out.
     */
    public void flushWriters() {
        writers.forEach((filename, writer) -> fileManifest.writeFile(filename, writer.toString()));
        writers.clear();
    }

    /**
     * Gets all the dependencies that have been registered in writers owned by the
     * delegator.
     *
     * @return Returns all the dependencies.
     */
    public List<SymbolDependency> getDependencies() {
        List<SymbolDependency> resolved = new ArrayList<>();
        writers.values().forEach(s -> resolved.addAll(s.getDependencies()));
        return resolved;
    }

    /**
     * Gets a previously created writer or creates a new one if needed
     * and adds a new line if the writer already exists.
     *
     * @param filename       Name of the file to create.
     * @param namespace      Namespace of the file's content.
     * @param writerConsumer Consumer that accepts and works with the file.
     */
    public void useFileWriter(String filename, String namespace, Consumer<GoWriter> writerConsumer) {
        writerConsumer.accept(checkoutWriter(filename, namespace));
    }

    GoWriter checkoutWriter(String filename, String namespace) {
        String formattedFilename = Paths.get(filename).normalize().toString();
        boolean needsNewline = writers.containsKey(formattedFilename);

        GoWriter writer = writers.computeIfAbsent(formattedFilename, f -> new GoWriter(namespace));

        if (needsNewline) {
            writer.write("\n");
        }

        return writer;
    }
}
