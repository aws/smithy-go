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

package software.amazon.smithy.go.codegen;

import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;
import software.amazon.smithy.go.codegen.knowledge.GoPointableIndex;
import software.amazon.smithy.go.codegen.serde2.StructureDeserializer;
import software.amazon.smithy.go.codegen.util.ShapeUtil;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.CollectionShape;
import software.amazon.smithy.model.shapes.MapShape;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.ErrorTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.utils.MapUtils;
import software.amazon.smithy.utils.SetUtils;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Renders structures.
 */
@SmithyInternalApi
public final class StructureGenerator implements Runnable {
    private static final Map<String, String> STANDARD_ERROR_MEMBERS = MapUtils.of(
            "ErrorCode", "string",
            "ErrorMessage", "string",
            "ErrorFault", "string"
    );
    private static final Set<String> ERROR_MEMBER_NAMES = SetUtils.of("ErrorMessage", "Message", "ErrorCodeOverride");

    private final Model model;
    private final SymbolProvider symbolProvider;
    private final GoWriter writer;
    private final StructureShape shape;
    private Symbol symbol;
    private final ServiceShape service;
    private final ProtocolGenerator protocolGenerator;
    private final boolean useExperimentalSerde;
    private final GoCodegenContext ctx;
    private final GoPointableIndex nilIndex;

    public StructureGenerator(
            GoCodegenContext ctx,
            GoWriter writer,
            StructureShape shape,
            ProtocolGenerator protocolGenerator
    ) {
        this.ctx = ctx;
        this.model = ctx.model();
        this.symbolProvider = ctx.symbolProvider();
        this.writer = writer;
        this.service = ctx.service();
        this.shape = shape;
        this.symbol = ctx.symbolProvider().toSymbol(shape);
        this.protocolGenerator = protocolGenerator;
        this.useExperimentalSerde = ctx.settings().useExperimentalSerde();
        this.nilIndex = GoPointableIndex.of(model);
    }

    public StructureGenerator(
            GoCodegenContext ctx,
            GoWriter writer,
            StructureShape shape,
            ProtocolGenerator protocolGenerator,
            Symbol symbolOverride
    ) {
        this(ctx, writer, shape, protocolGenerator);
        this.symbol = symbolOverride;
    }

    @Override
    public void run() {
        if (!shape.hasTrait(ErrorTrait.class)) {
            renderStructure(() -> {
            });
        } else {
            renderErrorStructure();
        }
    }

    /**
     * Renders a non-error structure.
     *
     * @param runnable A runnable that runs before the structure definition is closed. This can be used to write
     *                 additional members.
     */
    public void renderStructure(Runnable runnable) {
        renderStructure(runnable, false);
    }

    /**
     * Renders a non-error structure.
     *
     * @param runnable         A runnable that runs before the structure definition is closed. This can be used to write
     *                         additional members.
     * @param isInputStructure A boolean indicating if input variants for member symbols should be used.
     */
    public void renderStructure(Runnable runnable, boolean isInputStructure) {
        writer.writeShapeDocs(shape);
        writer.openBlock("type $L struct {", symbol.getName());

        CodegenUtils.SortedMembers sortedMembers = new CodegenUtils.SortedMembers(symbolProvider);
        shape.getAllMembers().values().stream()
                .filter(memberShape -> !StreamingTrait.isEventStream(model, memberShape))
                .sorted(sortedMembers)
                .forEach((member) -> {
                    writer.write("");

                    String memberName = symbolProvider.toMemberName(member);
                    writer.writeMemberDocs(model, member);

                    Symbol memberSymbol = symbolProvider.toSymbol(member);
                    if (isInputStructure) {
                        memberSymbol = memberSymbol.getProperty(SymbolUtils.INPUT_VARIANT, Symbol.class)
                                .orElse(memberSymbol);
                    }

                    writer.write("$L $P", memberName, memberSymbol);
                });

        runnable.run();

        // At this moment there is no support for the concept of modeled document structure types.
        // We embed the NoSerde type to prevent usage of the generated structure shapes from being used
        // as document types themselves, or part of broader document-type structures. This avoids making backwards
        // incompatible changes if the document type representation changes if it is later annotated as a modeled
        // document type. This restriction may be relaxed later by removing this constraint.
        writer.write("");
        writer.write("$L", ProtocolDocumentGenerator.NO_DOCUMENT_SERDE_TYPE_NAME);

        writer.closeBlock("}").write("");

        if (useExperimentalSerde) {
            generateSerializers();
            writer.write(new StructureDeserializer(ctx, shape));
        }
    }

    /**
     * Renders an error structure and supporting methods.
     */
    private void renderErrorStructure() {
        Symbol structureSymbol = symbolProvider.toSymbol(shape);
        writer.addUseImports(SmithyGoDependency.SMITHY);
        writer.addUseImports(SmithyGoDependency.FMT);
        ErrorTrait errorTrait = shape.expectTrait(ErrorTrait.class);

        // Write out a struct to hold the error data.
        writer.writeShapeDocs(shape);
        writer.openBlock("type $L struct {", "}", structureSymbol.getName(), () -> {
            // The message is the only part of the standard APIError interface that isn't known ahead of time.
            // Message is a pointer mostly for the sake of consistency.
            writer.write("Message *string").write("");
            writer.write("ErrorCodeOverride *string").write("");

            for (MemberShape member : shape.getAllMembers().values()) {
                String memberName = symbolProvider.toMemberName(member);
                // error messages are represented under Message for consistency
                if (!ERROR_MEMBER_NAMES.contains(memberName)) {
                    writer.write("$L $P", memberName, symbolProvider.toSymbol(member));
                }
            }

            writer.write("");
            writer.write("$L", ProtocolDocumentGenerator.NO_DOCUMENT_SERDE_TYPE_NAME);
        }).write("");

        // write the Error method to satisfy the standard error interface
        writer.openBlock("func (e *$L) Error() string {", "}", structureSymbol.getName(), () -> {
            writer.write("return fmt.Sprintf(\"%s: %s\", e.ErrorCode(), e.ErrorMessage())");
        });

        // Write out methods to satisfy the APIError interface. All but the message are known ahead of time,
        // and for those we just encode the information in the method itself.
        writer.openBlock("func (e *$L) ErrorMessage() string {", "}", structureSymbol.getName(), () -> {
            writer.openBlock("if e.Message == nil {", "}", () -> {
                writer.write("return \"\"");
            });
            writer.write("return *e.Message");
        });

        String errorCode = protocolGenerator == null ? shape.getId().getName(service)
                : protocolGenerator.getErrorCode(service, shape);
        writer.openBlock("func (e *$L) ErrorCode() string {", "}", structureSymbol.getName(), () -> {
            writer.openBlock("if e == nil || e.ErrorCodeOverride == nil {", "}", () -> {
                writer.write("return $S", errorCode);
            });
            writer.write("return *e.ErrorCodeOverride");
        });

        String fault = "smithy.FaultUnknown";
        if (errorTrait.isClientError()) {
            fault = "smithy.FaultClient";
        } else if (errorTrait.isServerError()) {
            fault = "smithy.FaultServer";
        }
        writer.write("func (e *$L) ErrorFault() smithy.ErrorFault { return $L }", structureSymbol.getName(), fault);
    }

    private void generateSerializers() {
        writer.addImport(ctx.settings().getModuleName() + "/schemas", "schemas");

        var symbol = symbolProvider.toSymbol(shape);
        var members = shape.members().stream()
                .sorted(Comparator.comparing(MemberShape::getMemberName))
                .toList();
        writer.addUseImports(SmithyGoDependency.SMITHY);
        writer.openBlock("func (v *$L) Serialize(s smithy.ShapeSerializer) {", "}", symbol.getName(), () -> {
            writer.write("s.WriteMap(schemas.$L)", SchemaGenerator.getSchemaName(shape));
            for (var member : members) {
                var target = ShapeUtil.expectMember(model, shape, member.getMemberName());
                var ident = String.format("v.%s", symbolProvider.toMemberName(member));
                generateSerializeMember(0, member, target, ident, 0);
            }
            writer.write("s.CloseMap()");
        });
        writer.write("");
    }

    private void generateSerializeMember(int type, MemberShape member, Shape target, String ident, int depth) {
        String schemaName;

        // TODO this is stupid
        if (type == 0) { // struct
            schemaName = "schemas." + SchemaGenerator.getMemberSchemaName(shape, member);
        } else {
            schemaName = "nil";
        }

        var ptrSuffix = nilIndex.isNillable(member) ? "Ptr" : "";
        switch (target.getType()) {
            case BYTE ->
                    writer.write("s.WriteInt8$L($L, $L)", ptrSuffix, schemaName, ident);
            case SHORT ->
                    writer.write("s.WriteInt16$L($L, $L)", ptrSuffix, schemaName, ident);
            case INTEGER ->
                    writer.write("s.WriteInt32$L($L, $L)", ptrSuffix, schemaName, ident);
            case LONG ->
                    writer.write("s.WriteInt64$L($L, $L)", ptrSuffix, schemaName, ident);

            case FLOAT ->
                    writer.write("s.WriteFloat32$L($L, $L)", ptrSuffix, schemaName, ident);
            case DOUBLE ->
                    writer.write("s.WriteFloat64$L($L, $L)", ptrSuffix, schemaName, ident);

            case BOOLEAN ->
                    writer.write("s.WriteBool$L($L, $L)", ptrSuffix, schemaName, ident);
            case STRING ->
                    writer.write("s.WriteString$L($L, $L)", ptrSuffix, schemaName, ident);

            case MAP -> // TODO: sort fields (or the option to?)
                    writer.write("serialize$L(s, $L, $L)", target.getId().getName(), schemaName, ident);

            case TIMESTAMP -> writer.write("s.WriteTime($L, $L)", schemaName, ident);

            case BLOB -> writer.write("s.WriteBlob($L, $L)", schemaName, ident);

            case ENUM -> writer.write("s.WriteString($L, string($L))", schemaName, ident);
            case INT_ENUM -> writer.write("s.WriteInt32($L, int32($L))", schemaName, ident);

            case STRUCTURE ->
                writer.openBlock("s.WriteMap($L, func() {", " })", schemaName, () -> {
                    writer.write("$L.SerializeFields(s)", ident);
                });

            case LIST, SET -> writer.write("// TODO: list $L", ident);

            case UNION -> writer.write("// TODO: union $L", ident);

            case DOCUMENT -> writer.write("// TODO: document $L", ident);

            // FUTURE(602)
            case BIG_INTEGER, BIG_DECIMAL -> throw new UnsupportedShapeException(target.getType());

            // invalid in this context
            case MEMBER, SERVICE, RESOURCE, OPERATION -> throw new UnsupportedShapeException(target.getType());
        }
    }
}
