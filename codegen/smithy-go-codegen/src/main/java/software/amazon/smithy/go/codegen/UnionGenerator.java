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
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.SimpleShape;
import software.amazon.smithy.model.shapes.UnionShape;

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
        writer.writeShapeDocs(shape);

        // Creates the parent interface for the union, which only defines a
        // non-exported method whose purpose is only to enable satisfying the
        // interface.
        writer.openBlock("type $L interface {", "}", symbol.getName(), () -> {
            writer.write("is$L()", symbol.getName());
        }).write("");

        // Create structs for each member that satisfy the interface.
        for (MemberShape member : shape.getAllMembers().values()) {
            Symbol memberSymbol = symbolProvider.toSymbol(member);
            String exportedMemberName = symbol.getName() + symbolProvider.toMemberName(member);
            Shape target = model.expectShape(member.getTarget());

            // Create the member's concrete type
            writer.writeMemberDocs(model, member);
            writer.openBlock("type $L struct {", "}", exportedMemberName, () -> {
                // Union members can't have null values, so for simple shapes we don't
                // use pointers. We have to use pointers for complex shapes since,
                // for example, we could still have a map that's empty or which has
                // null values.
                if (target instanceof SimpleShape) {
                    writer.write("Value $T", memberSymbol);
                } else {
                    writer.write("Value $P", memberSymbol);
                }
            });

            writer.write("func ($L) is$L() {}", exportedMemberName, symbol.getName());
        }

        // Creates a fallback type for use when an unknown member is found. This
        // could be the result of an outdated client, for example.
        String unknownStructName = symbol.getName() + "Unknown";
        writer.writeDocs(unknownStructName
                + " is returned when a union member is returned over the wire, but has an unknown tag.");
        writer.openBlock("type $L struct {", "}", unknownStructName, () -> {
            // The tag (member) name received over the wire.
            writer.write("Tag string");
            // The value received.
            writer.write("Value []byte");
        });

        writer.write("func (v $L) is$L() {}", unknownStructName, symbol.getName());
    }
}
