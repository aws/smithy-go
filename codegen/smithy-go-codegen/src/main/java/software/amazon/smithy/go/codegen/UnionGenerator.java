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
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.utils.StringUtils;

/**
 * Renders unions and type aliases for all their members.
 */
public class UnionGenerator implements Runnable {

    private final Model model;
    private final SymbolProvider symbolProvider;
    private final GoWriter writer;
    private final UnionShape shape;

    UnionGenerator(Model model, SymbolProvider symbolProvider, GoWriter writer, UnionShape shape) {
        this.model = model;
        this.symbolProvider = symbolProvider;
        this.writer = writer;
        this.shape = shape;
    }

    @Override
    public void run() {
        Symbol symbol = symbolProvider.toSymbol(shape);

        // Creates the parent interface for the union, which only defines a
        // non-exported method whose purpose is only to enable satisfying the
        // interface.
        writer.openBlock("type $L interface {", "}", symbol.getName(), () -> {
            writer.write("is$L()", symbol.getName());
        }).write("");

        // Create type aliases for each member that satisfy the interface.
        for (MemberShape member : shape.getAllMembers().values()) {
            Symbol memberSymbol = symbolProvider.toSymbol(member);
            String exportedMemberName = symbol.getName() + symbolProvider.toMemberName(member);
            String unExportedMemberName = StringUtils.uncapitalize(exportedMemberName);

            // Create an interface for the member
            writer.openBlock("type $L interface {", "}", exportedMemberName, () -> {
                writer.write("$L", symbol.getName());
                writer.write("is$L()", exportedMemberName);
                writer.write("Value() $T", memberSymbol);
            });

            writer.openBlock("func As$L(v $T) $L {", "}", exportedMemberName, memberSymbol, exportedMemberName, () -> {
                writer.write("return $L{value: v}", unExportedMemberName);
            });

            // Create the member's un-exported concrete type
            writer.openBlock("type $L struct {", "}", unExportedMemberName, () -> {
                writer.write("value $T", memberSymbol);
            });

            writer.write("func ($L) is$L() {}", unExportedMemberName, symbol.getName());
            writer.write("func ($L) is$L() {}", unExportedMemberName, exportedMemberName);
            writer.openBlock("func (v $L) Value() $T {", "}", unExportedMemberName, memberSymbol, () -> {
                writer.write("return v.value");
            });
        }

        // Creates a fallback type for use when an unknown member is found. This
        // could be the result of an outdated client, for example.
        writer.openBlock("type $LUnknown struct {", "}", symbol.getName(), () -> {
            // The tag (member) name received over the wire. Necessary for re-serialization.
            writer.write("tag string");
            // The value received.
            writer.write("value []byte");
        });
        writer.write("func (v $LUnknown) is$L() {}", symbol.getName(), symbol.getName())
                .write("func (v $LUnknown) Value() []byte { return v.value }", symbol.getName())
                .write("func (v $LUnknown) Tag() string { return v.tag }", symbol.getName());
    }
}
