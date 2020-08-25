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

import java.util.Collection;
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
    public static final String UNKNOWN_MEMBER_NAME = "UnknownUnionMember";

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
            String exportedMemberName = String.format(
                    "%sMember%s", symbol.getName(), symbolProvider.toMemberName(member));
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

            writer.write("func (*$L) is$L() {}", exportedMemberName, symbol.getName());
        }
    }

    /**
     * Generates a struct for unknown union values that applies to every union in the given set.
     *
     * @param writer The writer to write the union to.
     * @param unions A set of unions whose interfaces the union should apply to.
     * @param symbolProvider A symbol provider used to get the symbols for the unions.
     */
    public static void generateUnknownUnion(
            GoWriter writer,
            Collection<UnionShape> unions,
            SymbolProvider symbolProvider
    ) {
        // Creates a fallback type for use when an unknown member is found. This
        // could be the result of an outdated client, for example.
        writer.writeDocs(UNKNOWN_MEMBER_NAME
                + " is returned when a union member is returned over the wire, but has an unknown tag.");
        writer.openBlock("type $L struct {", "}", UNKNOWN_MEMBER_NAME, () -> {
            // The tag (member) name received over the wire.
            writer.write("Tag string");
            // The value received.
            writer.write("Value []byte");
        });

        for (UnionShape union : unions) {
            writer.write("func (*$L) is$L() {}", UNKNOWN_MEMBER_NAME, symbolProvider.toSymbol(union).getName());
        }
    }
}
