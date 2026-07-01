/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.go.codegen.integration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.SmithyGoDependency;
import software.amazon.smithy.go.codegen.Writable;
import software.amazon.smithy.go.codegen.knowledge.GoPointableIndex;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ListShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.OperationShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.UnionShape;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.traits.StreamingTrait;

/**
 * Generates fully-populated Go struct literals for snapshot testing. For each operation input, produces one or more
 * test case inputs covering all union variants additively.
 */
public final class SnapshotInputGenerator {
    private static final int COLLECTION_SIZE = 2;

    // Maps emit a single entry: Go map iteration order is randomized, so a map with
    // more than one entry would serialize to a nondeterministic byte order and make
    // snapshots flaky. One entry exercises the map serialization path deterministically
    // across every protocol.
    private static final int MAP_SIZE = 1;

    private final Model model;
    private final SymbolProvider symbolProvider;
    private final GoPointableIndex pointableIndex;

    public SnapshotInputGenerator(Model model, SymbolProvider symbolProvider) {
        this.model = model;
        this.symbolProvider = symbolProvider;
        this.pointableIndex = GoPointableIndex.of(model);
    }

    /**
     * A single snapshot test case: a name suffix and the writable that emits the Go input literal.
     */
    public record TestCase(String suffix, Writable input) {}

    /**
     * Generates test cases for a given operation. Returns one case per union variant found in the input tree.
     * If there are no unions, returns a single case with empty suffix.
     */
    public List<TestCase> generateCases(OperationShape operation) {
        var inputShape = model.expectShape(operation.getInputShape(), StructureShape.class);
        return List.of(new TestCase("", writer -> {
            writeStructure(writer, inputShape, new HashSet<>(), UnionChoice.DEFAULT, true);
        }));
    }


    private static final int MAX_DEPTH = 10;

    private void writeStructure(GoWriter writer, StructureShape shape, Set<software.amazon.smithy.model.shapes.ShapeId> visited, UnionChoice choice,
                               boolean pointable) {
        if (!visited.add(shape.getId()) || visited.size() > MAX_DEPTH) {
            if (pointable) {
                writer.writeInline("nil");
            } else {
                writer.writeInline("$T{}", symbolProvider.toSymbol(shape));
            }
            return;
        }

        var symbol = symbolProvider.toSymbol(shape);
        var addr = pointable ? "&" : "";
        writer.write("$L$T{", addr, symbol);
        writer.indent();
        for (var member : shape.getAllMembers().values()) {
            var target = model.expectShape(member.getTarget());
            if (target.hasTrait(StreamingTrait.class)) {
                continue;
            }
            var memberName = symbolProvider.toMemberName(member);
            writer.writeInline("$L: ", memberName);
            writeMemberValue(writer, member, target, visited, choice);
            writer.write(",");
        }
        writer.dedent();
        writer.writeInline("}");

        visited.remove(shape.getId());
    }

    private void writeMemberValue(
            GoWriter writer, MemberShape member, Shape target, Set<software.amazon.smithy.model.shapes.ShapeId> visited, UnionChoice choice
    ) {
        boolean needsPointer = pointableIndex.isPointable(member);
        switch (target.getType()) {
            case BOOLEAN -> writeScalar(writer, needsPointer, "true", SmithyGoDependency.SMITHY_PTR, "Bool");
            case BYTE -> writeScalar(writer, needsPointer, "1", SmithyGoDependency.SMITHY_PTR, "Int8");
            case SHORT -> writeScalar(writer, needsPointer, "1", SmithyGoDependency.SMITHY_PTR, "Int16");
            case INTEGER -> writeScalar(writer, needsPointer, "1", SmithyGoDependency.SMITHY_PTR, "Int32");
            case LONG -> writeScalar(writer, needsPointer, "1", SmithyGoDependency.SMITHY_PTR, "Int64");
            case FLOAT -> writeScalar(writer, needsPointer, "1.0", SmithyGoDependency.SMITHY_PTR, "Float32");
            case DOUBLE -> writeScalar(writer, needsPointer, "1.0", SmithyGoDependency.SMITHY_PTR, "Float64");
            case STRING -> {
                if (target.hasTrait(EnumTrait.class)) {
                    // IDL1 string with @enum trait
                    var enumSymbol = symbolProvider.toSymbol(target);
                    var firstValue = target.expectTrait(EnumTrait.class)
                            .getValues().get(0).getValue();
                    writer.writeInline("$T($S)", enumSymbol, firstValue);
                } else if (target.isEnumShape()) {
                    // IDL2 enum shape appearing as STRING type (shouldn't happen, but defensive)
                    var enumSymbol = symbolProvider.toSymbol(target);
                    var firstMember = target.getAllMembers().values().iterator().next();
                    var memberSymbol = symbolProvider.toSymbol(firstMember);
                    writer.writeInline("$T", memberSymbol);
                } else {
                    var memberName = symbolProvider.toMemberName(member);
                    if (member.hasTrait(software.amazon.smithy.model.traits.HostLabelTrait.class)) {
                        writeStringValue(writer, needsPointer, memberName + "-value");
                    } else {
                        writeStringValue(writer, needsPointer, "__" + memberName + "__");
                    }
                }
            }
            case ENUM -> {
                var enumSymbol = symbolProvider.toSymbol(target);
                var members = target.getAllMembers().values();
                if (members.isEmpty()) {
                    writer.writeInline("$T(\"\")", enumSymbol);
                } else {
                    var firstMember = members.iterator().next();
                    var value = firstMember.expectTrait(
                            software.amazon.smithy.model.traits.EnumValueTrait.class).expectStringValue();
                    writer.writeInline("$T($S)", enumSymbol, value);
                }
            }
            case INT_ENUM -> {
                var enumSymbol = symbolProvider.toSymbol(target);
                var members = target.getAllMembers().values();
                if (members.isEmpty()) {
                    writer.writeInline("$T(0)", enumSymbol);
                } else {
                    var firstMember = members.iterator().next();
                    var value = firstMember.expectTrait(
                            software.amazon.smithy.model.traits.EnumValueTrait.class).expectIntValue();
                    writer.writeInline("$T($L)", enumSymbol, value);
                }
            }
            case TIMESTAMP -> {
                writer.addUseImports(SmithyGoDependency.SMITHY_PTR);
                writer.addUseImports(SmithyGoDependency.TIME);
                if (needsPointer) {
                    writer.writeInline("ptr.Time(time.Date(2000, 1, 1, 0, 0, 0, 0, time.UTC))");
                } else {
                    writer.writeInline("time.Date(2000, 1, 1, 0, 0, 0, 0, time.UTC)");
                }
            }
            case BLOB -> {
                writer.writeInline("[]byte(\"blob\")");
            }
            case LIST -> writeList(writer, target.asListShape().get(), visited, choice);
            case MAP -> writeMap(writer, target.asMapShape().get(), visited, choice);
            case STRUCTURE -> writeStructure(writer, target.asStructureShape().get(), visited, choice,
                    needsPointer);
            case UNION -> writeUnion(writer, target.asUnionShape().get(), visited, choice);
            case DOCUMENT -> writer.writeInline("nil");
            default -> writer.writeInline("nil");
        }
    }

    private void writeScalar(GoWriter writer, boolean needsPointer, String value,
                             software.amazon.smithy.go.codegen.GoDependency ptrDep, String ptrFunc) {
        if (needsPointer) {
            writer.addUseImports(ptrDep);
            writer.writeInline("ptr.$L($L)", ptrFunc, value);
        } else {
            writer.writeInline("$L", value);
        }
    }

    private void writeStringValue(GoWriter writer, boolean needsPointer, String value) {
        if (needsPointer) {
            writer.addUseImports(SmithyGoDependency.SMITHY_PTR);
            writer.writeInline("ptr.String($S)", value);
        } else {
            writer.writeInline("$S", value);
        }
    }

    private void writeList(GoWriter writer, ListShape shape, Set<software.amazon.smithy.model.shapes.ShapeId> visited, UnionChoice choice) {
        var memberTarget = model.expectShape(shape.getMember().getTarget());

        writer.write("$T{", symbolProvider.toSymbol(shape));
        writer.indent();
        for (int i = 0; i < COLLECTION_SIZE; i++) {
            writeMemberValue(writer, shape.getMember(), memberTarget, visited, choice);
            writer.write(",");
        }
        writer.dedent();
        writer.writeInline("}");
    }

    private void writeMap(GoWriter writer, MapShape shape, Set<software.amazon.smithy.model.shapes.ShapeId> visited, UnionChoice choice) {
        var valueTarget = model.expectShape(shape.getValue().getTarget());

        writer.write("$T{", symbolProvider.toSymbol(shape));
        writer.indent();
        for (int i = 0; i < MAP_SIZE; i++) {
            writer.writeInline("\"key$L\": ", i);
            writeMemberValue(writer, shape.getValue(), valueTarget, visited, choice);
            writer.write(",");
        }
        writer.dedent();
        writer.writeInline("}");
    }

    private void writeUnion(GoWriter writer, UnionShape shape, Set<software.amazon.smithy.model.shapes.ShapeId> visited, UnionChoice choice) {
        var members = shape.getAllMembers().values().stream().toList();
        boolean recursing = visited.contains(shape.getId());

        MemberShape chosenMember;
        if (recursing) {
            // We've recursed back into this union (e.g. a variant that is a
            // list/map/member of the same union). Emitting nil here produces an
            // invalid payload -- a nil element still occupies a slot in a list and
            // serializes to garbage. Instead pick the first variant that terminates
            // the recursion: one whose subtree doesn't reach back into a shape we're
            // already building.
            chosenMember = members.stream()
                    .filter(m -> !reachesVisited(model.expectShape(m.getTarget()), visited))
                    .findFirst()
                    .orElse(null);
            if (chosenMember == null) {
                // Every variant recurses; the type has no finite value to generate.
                writer.writeInline("nil");
                return;
            }
        } else {
            visited.add(shape.getId());
            chosenMember = members.get(choice.indexFor(shape) % members.size());
        }

        var chosenTarget = model.expectShape(chosenMember.getTarget());

        var memberSymbol = software.amazon.smithy.go.codegen.SymbolUtils.createPointableSymbolBuilder(
                symbolProvider.toMemberName(chosenMember),
                symbolProvider.toSymbol(shape).getNamespace()
        ).build();
        writer.write("&$T{", memberSymbol);
        writer.indent();
        writer.writeInline("Value: ");
        switch (chosenTarget.getType()) {
            case STRUCTURE -> writeStructure(writer, chosenTarget.asStructureShape().get(), visited, choice,
                    pointableIndex.isPointable(chosenMember));
            case LIST -> writeList(writer, chosenTarget.asListShape().get(), visited, choice);
            case MAP -> writeMap(writer, chosenTarget.asMapShape().get(), visited, choice);
            case UNION -> writeUnion(writer, chosenTarget.asUnionShape().get(), visited, choice);
            default -> writeMemberValue(writer, chosenMember, chosenTarget, visited, choice);
        }
        writer.write(",");
        writer.dedent();
        writer.writeInline("}");

        if (!recursing) {
            visited.remove(shape.getId());
        }
    }

    /**
     * Returns true if the given shape can transitively reach any shape currently being built (i.e. present in
     * {@code visited}), which would form a cycle.
     */
    private boolean reachesVisited(Shape shape, Set<software.amazon.smithy.model.shapes.ShapeId> visited) {
        return reachesVisited(shape, visited, new HashSet<>());
    }

    private boolean reachesVisited(
            Shape shape,
            Set<software.amazon.smithy.model.shapes.ShapeId> visited,
            Set<software.amazon.smithy.model.shapes.ShapeId> seen
    ) {
        if (visited.contains(shape.getId())) {
            return true;
        }
        if (!seen.add(shape.getId())) {
            return false;
        }

        for (var member : shape.getAllMembers().values()) {
            if (reachesVisited(model.expectShape(member.getTarget()), visited, seen)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Controls which union variant to select.
     */
    private record UnionChoice(software.amazon.smithy.model.shapes.ShapeId targetUnion, int variantIndex) {
        static final UnionChoice DEFAULT = new UnionChoice(null, 0);

        int indexFor(UnionShape shape) {
            if (targetUnion != null && shape.getId().equals(targetUnion)) {
                return variantIndex;
            }
            return 0;
        }
    }
}
