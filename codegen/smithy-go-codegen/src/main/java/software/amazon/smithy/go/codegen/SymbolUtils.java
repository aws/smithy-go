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

import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolReference;
import software.amazon.smithy.model.shapes.Shape;

/*
* Common symbol utility building functions
 */
public final class SymbolUtils {
    private SymbolUtils() {
    }

    /**
     * Create a value symbol builder
     *
     * @param typeName the name of the type
     * @return the symbol builder type
     */
    public static Symbol.Builder createValueSymbolBuilder(String typeName) {
        return Symbol.builder()
                .putProperty("pointable", false)
                .name(typeName);
    }

    /**
     * Create a pointable symbol builder
     *
     * @param typeName the name of the type
     * @return the symbol builder
     */
    public static Symbol.Builder createPointableSymbolBuilder(String typeName) {
        return Symbol.builder()
                .putProperty("pointable", true)
                .name(typeName);
    }

    /**
     * Create a value symbol builder
     *
     * @param shape the shape that the type is for
     * @param typeName the name of the type
     * @return the symbol builder
     */
    public static Symbol.Builder createValueSymbolBuilder(Shape shape, String typeName) {
        return createValueSymbolBuilder(typeName).putProperty("shape", shape);
    }

    /**
     * Create a pointable symbol builder
     *
     * @param shape the shape that the type is for
     * @param typeName the name of the type
     * @return the symbol builder
     */
    public static Symbol.Builder createPointableSymbolBuilder(Shape shape, String typeName) {
        return createPointableSymbolBuilder(typeName).putProperty("shape", shape);
    }

    /**
     * Create a pointable symbol builder
     *
     * @param typeName the name of the type
     * @param namespace the namespace of the type
     * @return the symbol builder
     */
    public static Symbol.Builder createPointableSymbolBuilder(String typeName, String namespace) {
        return createPointableSymbolBuilder(typeName).namespace(namespace, ".");
    }

    /**
     * Create a value symbol builder
     *
     * @param typeName the name of the type
     * @param namespace the namespace of the type
     * @return the symbol builder
     */
    public static Symbol.Builder createValueSymbolBuilder(String typeName, String namespace) {
        return createValueSymbolBuilder(typeName).namespace(namespace, ".");
    }

    /**
     * Create a pointable symbol builder
     *
     * @param shape the shape that the type is for
     * @param typeName the name of the type
     * @param namespace the namespace of the type
     * @return the symbol builder
     */
    public static Symbol.Builder createPointableSymbolBuilder(Shape shape, String typeName, String namespace) {
        return createPointableSymbolBuilder(shape, typeName).namespace(namespace, ".");
    }

    /**
     * Create a value symbol builder
     *
     * @param shape the shape that the type is for
     * @param typeName the name of the type
     * @param namespace the namespace of the type
     * @return the symbol builder
     */
    public static Symbol.Builder createValueSymbolBuilder(Shape shape, String typeName, String namespace) {
        return createValueSymbolBuilder(shape, typeName).namespace(namespace, ".");
    }

    /**
     * Create a symbol reference for a dependency
     *
     * @param dependency the dependency to represent as a symbol reference
     * @return the symbol reference
     */
    public static SymbolReference createNamespaceReference(GoDependency dependency) {
        String namespace = dependency.importPath;
        return createNamespaceReference(dependency, CodegenUtils.getDefaultPackageImportName(namespace));
    }

    /**
     * Create a symbol reference for a dependency
     *
     * @param dependency the dependency to represent as a symbol reference
     * @param alias the alias to refer to the namespace
     * @return the symbol reference
     */
    public static SymbolReference createNamespaceReference(GoDependency dependency, String alias) {
        // Go generally imports an entire package under a single name, which defaults to the last
        // part of the package name path. So we need to create a symbol for that namespace to reference.
        String namespace = dependency.importPath;
        Symbol namespaceSymbol = Symbol.builder()
                // We're not referencing a particular symbol from the namespace, so we leave the name blank.
                .name("")
                .putProperty("namespaceSymbol", true)
                .namespace(namespace, "/")
                .addDependency(dependency)
                .build();
        return SymbolReference.builder()
                .symbol(namespaceSymbol)
                .alias(alias)
                .build();
    }
}
