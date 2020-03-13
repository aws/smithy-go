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
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import software.amazon.smithy.build.FileManifest;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.Shape;

/**
 * Manages writers for Go files.
 */
final class GoDelegator {

    private final GoSettings settings;
    private final Model model;
    private final FileManifest fileManifest;
    private final SymbolProvider symbolProvider;
    private final Map<String, GoWriter> writers = new HashMap<>();

    GoDelegator(GoSettings settings, Model model, FileManifest fileManifest, SymbolProvider symbolProvider) {
        this.settings = settings;
        this.model = model;
        this.fileManifest = fileManifest;
        this.symbolProvider = symbolProvider;
    }

    /**
     * Writes all pending writers to disk and then clears them out.
     */
    void flushWriters() {
        writers.forEach((filename, writer) -> fileManifest.writeFile(filename, writer.toString()));
        writers.clear();
    }

    /**
     * Gets a previously created writer or creates a new one if needed.
     *
     * @param shape Shape to create the writer for.
     * @param writerConsumer Consumer that accepts and works with the file.
     */
    void useShapeWriter(Shape shape, Consumer<GoWriter> writerConsumer) {
        Symbol symbol = symbolProvider.toSymbol(shape);
        String namespace = symbol.getNamespace();
        if (namespace.equals(".")) {
            namespace = CodegenUtils.getDefaultPackageImportName(settings.getModuleName());
        }
        GoWriter writer = checkoutWriter(symbol.getDefinitionFile(), namespace);

        writer.pushState();
        writerConsumer.accept(writer);
        writer.popState();
    }

    private GoWriter checkoutWriter(String filename, String namespace) {
        String formattedFilename = Paths.get(filename).normalize().toString();
        boolean needsNewline = writers.containsKey(formattedFilename);

        GoWriter writer = writers.computeIfAbsent(formattedFilename, f -> new GoWriter(namespace));

        if (needsNewline) {
            writer.write("\n");
        }

        return writer;
    }
}
