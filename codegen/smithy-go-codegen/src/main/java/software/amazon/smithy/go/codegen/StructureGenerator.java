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

import java.util.Map;
import java.util.Set;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SetUtils;

/**
 * Renders structures.
 */
final class StructureGenerator implements Runnable {
    private static final Map<String, String> STANDARD_ERROR_MEMBERS = MapUtils.of(
            "ErrorCode", "string",
            "ErrorMessage", "string",
            "ErrorFault", "string"
    );
    private static final Set<String> ERROR_MESSAGE_MEMBER_NAMES = SetUtils.of("ErrorMessage", "Message");

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
        } else {
            renderErrorStructure();
        }
    }

    /**
     * Renders a normal, non-error structure.
     */
    private void renderStructure() {
        Symbol symbol = symbolProvider.toSymbol(shape);
        writer.writeShapeDocs(shape);
        writer.openBlock("type $L struct {", symbol.getName());
        for (MemberShape member : shape.getAllMembers().values()) {
            String memberName = symbolProvider.toMemberName(member);
            writer.writeMemberDocs(model, member);
            writer.write("$L $P", memberName, symbolProvider.toSymbol(member));
        }
        writer.closeBlock("}").write("");
    }

    /**
     * Renders an error structure and supporting methods.
     */
    private void renderErrorStructure() {
        Symbol structureSymbol = symbolProvider.toSymbol(shape);
        String interfaceName = structureSymbol.getName() + "Interface";

        ErrorTrait errorTrait = shape.expectTrait(ErrorTrait.class);

        // Write out the interface for the error
        writer.writeShapeDocs(shape);
        writer.openBlock("type $L interface {", "}", interfaceName, () -> {
            writer.write("smithy.APIError");

            // This non-exported method will be used when inheritance is introduced.
            writer.write("is$L()", structureSymbol.getName()).write("");

            for (Map.Entry<String, String> errorMember : STANDARD_ERROR_MEMBERS.entrySet()) {
                writer.write("$L() $L", errorMember.getKey(), errorMember.getValue());
            }
            writer.write("");

            for (MemberShape member : shape.getAllMembers().values()) {
                String memberName = symbolProvider.toMemberName(member);
                // Error messages are represented by the ErrorMessage function in the APIError interface,
                // so we don't generate getters for them.
                if (ERROR_MESSAGE_MEMBER_NAMES.contains(memberName)) {
                    continue;
                }
                Symbol memberSymbol = symbolProvider.toSymbol(member);
                String getterName = "Get" + memberName;
                String haserName = "Has" + memberName;

                writer.writeMemberDocs(model, member);
                writer.write("$L() $T", getterName, memberSymbol);
                writer.writeDocs(String.format("%s returns whether %s exists.", haserName, memberName));
                writer.write("$L() bool", haserName);
            }
        }).write("");

        // Write out a struct to hold the error data.
        writer.writeDocs(String.format(
                "The concrete implementation for %s. This should not be used directly.", interfaceName));
        writer.openBlock("type $L struct {", "}", structureSymbol.getName(), () -> {
            // The message is the only part of the standard APIError interface that isn't known ahead of time.
            // Message is a pointer mostly for the sake of consistency.
            writer.write("Message *string").write("");


            for (MemberShape member : shape.getAllMembers().values()) {
                String memberName = symbolProvider.toMemberName(member);
                // error messages are represented under Message for consistency
                if (!ERROR_MESSAGE_MEMBER_NAMES.contains(memberName)) {
                    writer.write("$L $P", memberName, symbolProvider.toSymbol(member));
                }
            }
        }).write("");

        // write the Error method to satisfy the standard error interface
        writer.openBlock("func (e *$L) Error() string {", "}", structureSymbol.getName(), () -> {
            writer.write("return fmt.Sprintf(\"%s: %s\", e.ErrorCode(), e.ErrorMessage())");
        });

        // Satisfy the isa function
        writer.write("func (e *$L) is$L() {}", structureSymbol.getName(), structureSymbol.getName());

        // Write out methods to satisfy the APIError interface. All but the message are known ahead of time,
        // and for those we just encode the information in the method itself.
        writer.openBlock("func (e *$L) ErrorMessage() string {", "}", structureSymbol.getName(), () -> {
            writer.openBlock("if e.Message == nil {", "}", () -> {
                writer.write("return \"\"");
            });
            writer.write("return *e.Message");
        });
        writer.write("func (e *$L) ErrorCode() string { return $S }",
                structureSymbol.getName(), shape.getId().getName());

        String fault = "smithy.FaultUnknown";
        if (errorTrait.isClientError()) {
            fault = "smithy.FaultClient";
        } else if (errorTrait.isServerError()) {
            fault = "smithy.FaultServer";
        }
        writer.write("func (e *$L) ErrorFault() smithy.ErrorFault { return $L }", structureSymbol.getName(), fault);

        // Write out methods to satisfy the error's specific interface
        for (MemberShape member : shape.getAllMembers().values()) {
            String memberName = symbolProvider.toMemberName(member);
            Symbol memberSymbol = symbolProvider.toSymbol(member);
            String getterName = "Get" + memberName;
            String haserName = "Has" + memberName;
            writer.openBlock("func (e *$L) $L() $T {", "}",
                    structureSymbol.getName(), getterName, memberSymbol, () -> {
                writer.write("return *e.$L", memberName);
            });
            writer.openBlock("func (e *$L) $L() bool {", "}", structureSymbol.getName(), haserName, () -> {
                writer.write("return e.$L != nil", memberName);
            });
        }
    }
}
