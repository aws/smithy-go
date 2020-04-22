package software.amazon.smithy.go.codegen;

import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolReference;

import java.util.Optional;
import java.util.function.BiConsumer;

public class GoMiddlewareGenerator {
    private final String identifier;
    private final String handleMethodName;
    private final SymbolReference inputType;
    private final SymbolReference outputType;
    private final SymbolReference handlerType;

    private static final SymbolReference contextType;
    private static final SymbolReference metadataType;

    static {
        contextType = SymbolReference.builder()
                .symbol(SymbolUtils.createValueSymbolBuilder("context.Context")
                        .addReference(SymbolUtils.createNamespaceReference(GoDependency.CONTEXT))
                        .build())
                .build();
        metadataType = SymbolReference.builder()
                .symbol(SymbolUtils.createValueSymbolBuilder("middleware.Metadata")
                        .addReference(SymbolUtils.createNamespaceReference(GoDependency.SMITHY_MIDDLEWARE))
                        .build())
                .build();
    }

    public GoMiddlewareGenerator(Builder builder) {
        this.identifier = builder.identifier;
        this.handleMethodName = builder.handleMethodName;
        this.inputType = builder.inputType;
        this.outputType = builder.outputType;
        this.handlerType = builder.handlerType;
    }

    public static GoMiddlewareGenerator newSerializeMiddleware(String identifier) {
        return builder()
                .identifier(identifier)
                .handleMethodName("HandleSerialize")
                .inputType(SymbolReference.builder()
                        .symbol(SymbolUtils.createValueSymbolBuilder("middleware.SerializeInput")
                                .addReference(SymbolUtils.createNamespaceReference(GoDependency.SMITHY_MIDDLEWARE))
                                .build())
                        .build())
                .outputType(SymbolReference.builder()
                        .symbol(SymbolUtils.createValueSymbolBuilder("middleware.SerializeOutput")
                                .addReference(SymbolUtils.createNamespaceReference(GoDependency.SMITHY_MIDDLEWARE))
                                .build())
                        .build())
                .handlerType(SymbolReference.builder()
                        .symbol(SymbolUtils.createValueSymbolBuilder("middleware.SerializeHandler")
                                .addReference(SymbolUtils.createNamespaceReference(GoDependency.SMITHY_MIDDLEWARE))
                                .build())
                        .build())
                .build();
    }

    public static GoMiddlewareGenerator newDeserializeMiddleware(String identifier) {
        return builder()
                .identifier(identifier)
                .handleMethodName("HandleDeserialize")
                .inputType(SymbolReference.builder()
                        .symbol(SymbolUtils.createValueSymbolBuilder("middleware.DeserializeInput")
                                .addReference(SymbolUtils.createNamespaceReference(GoDependency.SMITHY_MIDDLEWARE))
                                .build())
                        .build())
                .outputType(SymbolReference.builder()
                        .symbol(SymbolUtils.createValueSymbolBuilder("middleware.DeserializeOutput")
                                .addReference(SymbolUtils.createNamespaceReference(GoDependency.SMITHY_MIDDLEWARE))
                                .build())
                        .build())
                .handlerType(SymbolReference.builder()
                        .symbol(SymbolUtils.createValueSymbolBuilder("middleware.DeserializeHandler")
                                .addReference(SymbolUtils.createNamespaceReference(GoDependency.SMITHY_MIDDLEWARE))
                                .build())
                        .build())
                .build();
    }

    public void writeMiddleware(GoWriter writer, BiConsumer<GoMiddlewareGenerator, GoWriter> handlerBodyConsumer) {
        writeMiddleware(writer, (m, w) -> {
        }, handlerBodyConsumer);
    }

    public void writeMiddleware(GoWriter writer, BiConsumer<GoMiddlewareGenerator, GoWriter> fieldConsumer, BiConsumer<GoMiddlewareGenerator, GoWriter> handlerBodyConsumer) {
        writer.addUseImports(contextType);
        writer.addUseImports(metadataType);
        writer.addUseImports(inputType);
        writer.addUseImports(outputType);
        writer.addUseImports(handlerType);

        Symbol middlwareSym = Symbol.builder()
                .name(identifier)
                .putProperty("pointable", true)
                .build();

        writer.openBlock("type $L struct {", "}", middlwareSym, () -> {
            fieldConsumer.accept(this, writer);
        });
        writer.openBlock("func ($P) ID() string {", "}", middlwareSym, () -> {
            writer.openBlock("return $S", identifier);
        });
        writer.openBlock("func (m $P) $L(ctx $T, in $T, next $T) (\n" +
                        "\tout $T, metadata $T, err error,\n" +
                        "){", "}",
                new Object[]{middlwareSym, handleMethodName, contextType, inputType, handlerType, outputType, metadataType},
                () -> {
                    handlerBodyConsumer.accept(this, writer);
                });
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getHandleMethodName() {
        return handleMethodName;
    }

    public String getIdentifier() {
        return identifier;
    }

    public SymbolReference getInputType() {
        return inputType;
    }

    public SymbolReference getOutputType() {
        return outputType;
    }

    public SymbolReference getHandlerType() {
        return handlerType;
    }

    public static SymbolReference getContextType() {
        return contextType;
    }

    public static SymbolReference getMetadataType() {
        return metadataType;
    }

    public static class Builder {
        private String identifier;
        private String handleMethodName;
        private SymbolReference inputType;
        private SymbolReference outputType;
        private SymbolReference handlerType;

        public GoMiddlewareGenerator build() {
            return new GoMiddlewareGenerator(this);
        }

        public Builder handleMethodName(String handleMethodName) {
            this.handleMethodName = handleMethodName;
            return this;
        }

        public Builder identifier(String identifier) {
            this.identifier = identifier;
            return this;
        }

        public Builder inputType(SymbolReference inputType) {
            this.inputType = inputType;
            return this;
        }

        public Builder outputType(SymbolReference outputType) {
            this.outputType = outputType;
            return this;
        }

        public Builder handlerType(SymbolReference handlerType) {
            this.handlerType = handlerType;
            return this;
        }
    }
}
