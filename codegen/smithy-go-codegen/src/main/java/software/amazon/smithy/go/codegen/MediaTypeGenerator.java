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

import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.MediaTypeTrait;

/**
 * Renders media type shapes with their associated methods.
 */
final class MediaTypeGenerator implements Runnable {

    private final SymbolProvider symbolProvider;
    private final GoWriter writer;
    private final Shape shape;

    MediaTypeGenerator(SymbolProvider symbolProvider, GoWriter writer, Shape shape) {
        this.symbolProvider = symbolProvider;
        this.writer = writer;
        this.shape = shape;
    }

    @Override
    public void run() {
        Symbol symbol = symbolProvider.toSymbol(shape);

        Symbol baseType = symbol.getProperty("mediaTypeBaseSymbol", Symbol.class)
                .orElseThrow(() -> new CodegenException(
                        "Media type symbols must have the property mediaTypeBaseSymbol: " + shape.getId()));

        writer.writeShapeDocs(shape);
        writer.write("type $L $T", symbol.getName(), baseType)
                .write("")
                .openBlock("func (m $L) MediaType() string {", "}", symbol.getName(), () -> {
                    writer.write("return $S", shape.expectTrait(MediaTypeTrait.class).getValue());
                })
                .write("");
    }
}
