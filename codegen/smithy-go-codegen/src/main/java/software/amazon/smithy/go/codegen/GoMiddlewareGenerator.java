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

import java.util.function.BiConsumer;

import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolReference;

/*
 * Helper for generating Smithy Go middleware
 */
public class GoMiddlewareGenerator {
    private static final SymbolReference CONTEXT_TYPE;
    private static final SymbolReference METADATA_TYPE;

    private final String identifier;
    private final String handleMethodName;
    private final SymbolReference inputType;
    private final SymbolReference outputType;
    private final SymbolReference handlerType;

    static {
        CONTEXT_TYPE = SymbolReference.builder()
                .symbol(SymbolUtils.createValueSymbolBuilder("context.Context")
                        .addReference(SymbolUtils.createNamespaceReference(GoDependency.CONTEXT))
                        .build())
                .build();
        METADATA_TYPE = SymbolReference.builder()
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

    public void writeMiddleware(GoWriter writer, BiConsumer<GoMiddlewareGenerator, GoWriter> fieldConsumer,
                                BiConsumer<GoMiddlewareGenerator, GoWriter> handlerBodyConsumer) {
        writer.addUseImports(CONTEXT_TYPE);
        writer.addUseImports(METADATA_TYPE);
        writer.addUseImports(inputType);
        writer.addUseImports(outputType);
        writer.addUseImports(handlerType);

        Symbol middlwareSym = SymbolUtils.createPointableSymbolBuilder(identifier).build();

        writer.openBlock("type $L struct {", "}", middlwareSym, () -> {
            fieldConsumer.accept(this, writer);
        });
        writer.openBlock("func ($P) ID() string {", "}", middlwareSym, () -> {
            writer.openBlock("return $S", identifier);
        });
        writer.openBlock("func (m $P) $L(ctx $T, in $T, next $T) (\n"
                        + "\tout $T, metadata $T, err error,\n"
                        + "){", "}",
                new Object[]{
                        middlwareSym, handleMethodName, CONTEXT_TYPE, inputType, handlerType, outputType, METADATA_TYPE,
                },
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
        return CONTEXT_TYPE;
    }

    public static SymbolReference getMetadataType() {
        return METADATA_TYPE;
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
