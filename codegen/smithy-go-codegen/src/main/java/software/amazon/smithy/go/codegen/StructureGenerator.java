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
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.ErrorTrait;

/**
 * Renders structures.
 *
 * TODO: support errors
 */
final class StructureGenerator implements Runnable {

    private final Model model;
    private final SymbolProvider symbolProvider;
    private final GoWriter writer;
    private final StructureShape shape;

    StructureGenerator(Model model, SymbolProvider symbolProvider, GoWriter writer, StructureShape shape) {
        this.model = model;
        this.symbolProvider = symbolProvider;
        this.writer = writer;
        this.shape = shape;
    }

    @Override
    public void run() {
        if (!shape.hasTrait(ErrorTrait.class)) {
            renderStructure();
        }
    }

    /**
     * Renders a normal, non-error structure.
     */
    private void renderStructure() {
        Symbol symbol = symbolProvider.toSymbol(shape);
        writer.openBlock("type $L struct {", symbol.getName());
        writeMembers();
        writer.closeBlock("}").write("");
    }

    private void writeMembers() {
        for (MemberShape member : shape.getAllMembers().values()) {
            String memberName = symbolProvider.toMemberName(member);
            writer.write("$L $P", memberName, symbolProvider.toSymbol(member));
        }
    }
}
