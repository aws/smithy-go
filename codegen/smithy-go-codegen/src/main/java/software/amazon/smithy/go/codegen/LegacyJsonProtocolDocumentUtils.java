package software.amazon.smithy.go.codegen;

import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.go.codegen.integration.ProtocolGenerator;

// This is copied almost entirely as-is from JsonProtocolDocumentUtils. It is used for schema-serde to preserve the
// legacy APIs and behavior of documents, which at the time of migration, only supported JSON. Clients using
// schema-based serde don't use these, instead they use Value() and UnmarshalDocument() for requests and responses.
public final class LegacyJsonProtocolDocumentUtils {
    public static void generateProtocolDocumentMarshalerUnmarshalDocument(GoCodegenContext context, GoWriter writer) {
        writer.write("mBytes, err := m.$L()", ProtocolDocumentGenerator.MARSHAL_SMITHY_DOCUMENT_METHOD);
        writer.write("if err != nil { return err }").write("");
        writer.write("jDecoder := $T($T(mBytes))", SymbolUtils.createValueSymbolBuilder("NewDecoder",
                SmithyGoDependency.JSON).build(), SymbolUtils.createValueSymbolBuilder("NewReader",
                SmithyGoDependency.BYTES).build());
        writer.write("jDecoder.UseNumber()").write("");

        writer.write("var jv interface{}");
        writer.openBlock("if err := jDecoder.Decode(&v); err != nil {", "}", () -> writer.write("return err"))
                .write("");

        Symbol newUnmarshaler = ProtocolDocumentGenerator.Utilities.getInternalDocumentSymbolBuilder(
                        context.settings(), ProtocolDocumentGenerator.INTERNAL_NEW_DOCUMENT_UNMARSHALER_FUNC)
                .build();

        writer.write("return $T(v).$L(&jv)", newUnmarshaler,
                ProtocolDocumentGenerator.UNMARSHAL_SMITHY_DOCUMENT_METHOD);
    }

    public static void generateProtocolDocumentMarshalerMarshalDocument(GoCodegenContext context, GoWriter writer) {
        Symbol newEncoder = SymbolUtils.createValueSymbolBuilder("NewEncoder", SmithyGoDependency.SMITHY_DOCUMENT_JSON)
                .build();

        writer.write("return $T().Encode(m.value)", newEncoder);
    }

    public static void generateProtocolDocumentUnmarshalerUnmarshalDocument(GoCodegenContext context, GoWriter writer) {
        Symbol newDecoder = SymbolUtils.createValueSymbolBuilder("NewDecoder", SmithyGoDependency.SMITHY_DOCUMENT_JSON)
                .build();

        writer.write("decoder := $T()", newDecoder);
        writer.write("return decoder.DecodeJSONInterface(m.value, v)");
    }

    public static void generateProtocolDocumentUnmarshalerMarshalDocument(GoCodegenContext context, GoWriter writer) {
        writer.write("return $T(m.value)",
                SymbolUtils.createValueSymbolBuilder("Marshal", SmithyGoDependency.JSON).build());
    }
}
