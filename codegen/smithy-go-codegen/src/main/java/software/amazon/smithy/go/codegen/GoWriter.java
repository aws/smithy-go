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

import java.util.function.BiFunction;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.utils.CodeWriter;

/**
 * Specialized code writer for managing Go dependencies.
 *
 * <p>Use the {@code $T} formatter to refer to {@link Symbol}s.
 */
public final class GoWriter extends CodeWriter {

    private final String fullPackageName;

    public GoWriter(String fullPackageName) {
        this.fullPackageName = fullPackageName;
        trimBlankLines();
        trimTrailingSpaces();
        setIndentText("\t");
        putFormatter('T', new GoSymbolFormatter());
    }

    @Override
    public String toString() {
        String contents = super.toString();
        String[] packageParts = fullPackageName.split("/");
        String packageHeader = String.format("package %s%n%n", packageParts[packageParts.length - 1]);
        // TODO: add imports
        return packageHeader + contents;
    }

    /**
     * Implements Go symbol formatting for the {@code $T} formatter.
     */
    private static final class GoSymbolFormatter implements BiFunction<Object, String, String> {
        @Override
        public String apply(Object type, String indent) {
            if (type instanceof Symbol) {
                Symbol typeSymbol = (Symbol) type;
                // TODO: add imports
                return typeSymbol.getName();
            } else {
                throw new CodegenException(
                        "Invalid type provided to $T. Expected a Symbol, but found `" + type + "`");
            }
        }
    }
}
