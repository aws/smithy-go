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

package software.amazon.smithy.go.codegen.integration;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.ApplicationProtocol;
import software.amazon.smithy.go.codegen.GoSettings;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.utils.CaseUtils;
import software.amazon.smithy.utils.StringUtils;

/**
 * Smithy protocol code generators.
 */
public interface ProtocolGenerator {
    /**
     * Sanitizes the name of the protocol so it can be used as a symbol
     * in Go.
     *
     * <p>For example, the default implementation converts "." to "_",
     * and converts "-" to become camelCase separated words. This means
     * that "aws.rest-json-1.1" becomes "Aws_RestJson1_1".
     *
     * @param name Name of the protocol to sanitize.
     * @return Returns the sanitized name.
     */
    static String getSanitizedName(String name) {
        name = name.replaceAll("(\\s|\\.|-)+", "_");
        return CaseUtils.toCamelCase(name, true, '_');
    }

    /**
     * Gets the supported protocol {@link ShapeId}.
     *
     * @return Returns the protocol supported
     */
    ShapeId getProtocol();

    default String getProtocolName() {
        ShapeId protocol = getProtocol();
        String prefix = protocol.getNamespace();
        int idx = prefix.indexOf('.');
        if (idx != -1) {
            prefix = prefix.substring(0, idx);
        }
        return CaseUtils.toCamelCase(prefix) + getSanitizedName(protocol.getName());
    }

    /**
     * Creates an application protocol for the generator.
     *
     * @return Returns the created application protocol.
     */
    ApplicationProtocol getApplicationProtocol();

    /**
     * Determines if two protocol generators are compatible at the
     * application protocol level, meaning they both use HTTP, or MQTT
     * for example.
     *
     * <p>Two protocol implementations are considered compatible if the
     * {@link ApplicationProtocol#equals} method of {@link #getApplicationProtocol}
     * returns true when called with {@code other}. The default implementation
     * should work for most interfaces, but may be overridden for more in-depth
     * handling of things like minor version incompatibilities.
     *
     * <p>By default, if the application protocols are considered equal, then
     * {@code other} is returned.
     *
     * @param service            Service being generated.
     * @param protocolGenerators Other protocol generators that are being generated.
     * @param other              Protocol generator to resolve against.
     * @return Returns the resolved application protocol object.
     */
    default ApplicationProtocol resolveApplicationProtocol(
            ServiceShape service,
            Collection<ProtocolGenerator> protocolGenerators,
            ApplicationProtocol other
    ) {
        if (!getApplicationProtocol().equals(other)) {
            String protocolNames = protocolGenerators.stream()
                    .map(ProtocolGenerator::getProtocol)
                    .map(Trait::getIdiomaticTraitName)
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new CodegenException(String.format(
                    "All of the protocols generated for a service must be runtime compatible, but "
                            + "protocol `%s` is incompatible with other application protocols: [%s]. Please pick a "
                            + "set of compatible protocols using the `protocols` option when generating %s.",
                    getProtocol(), protocolNames, service.getId()));
        }

        return other;
    }

    /**
     * Generates any standard code for service request/response serde.
     *
     * @param context Serde context.
     */
    void generateSharedSerializerComponents(GenerationContext context);

    /**
     * Generates the code used to serialize the shapes of a service
     * for requests.
     *
     * @param context Serialization context.
     */
    void generateRequestSerializers(GenerationContext context);

    /**
     * Generates the code used to deserialize the shapes of a service
     * for responses.
     *
     * @param context Deserialization context.
     */
    void generateResponseDeserializers(GenerationContext context);

    /**
     * Generates the name of a serializer function for shapes of a service.
     *
     * @param shape    The shape the serializer function is being generated for.
     * @param protocol Name of the protocol being generated.
     * @return Returns the generated function name.
     */
    static String getOperationHttpBindingsSerFunctionName(Shape shape, String protocol) {
        return protocol
                + "_serializeHttpBindings"
                + StringUtils.capitalize(shape.getId().getName());
    }

    /**
     * Generates the name of a deserializer function for shapes of a service.
     *
     * @param symbol   The symbol the deserializer function is being generated for.
     * @param protocol Name of the protocol being generated.
     * @return Returns the generated function name.
     */
    static String getOperationDeserFunctionName(Symbol symbol, String protocol) {
        return protocol
                + "_deserializeHttpBindings"
                + symbol.getName();
    }

    /**
     * Generates the name of a serializer function for shapes of a service.
     *
     * @param shape    The shape the serializer function is being generated for.
     * @param protocol Name of the protocol being generated.
     * @return Returns the generated function name.
     */
    static String getDocumentSerializerFunctionName(Shape shape, String protocol) {
        return protocol + "_serializeDocument" + StringUtils.capitalize(shape.getId().getName());
    }

    /**
     * Generates the name of a deserializer function for shapes of a service.
     *
     * @param symbol        The symbol the deserializer function is being generated for.
     * @param protocol      Name of the protocol being generated.
     * @param protocolLayer the layer of the protocol being generated
     * @return Returns the generated function name.
     */
    static String getDeserFunctionName(Symbol symbol, String protocol, String protocolLayer) {
        // e.g., awsRestJson1_serializeRestFooShape
        String functionName = ProtocolGenerator.getSanitizedName(protocol)
                + "_deserialize"
                + ProtocolGenerator.getSanitizedName(protocolLayer);

        functionName += symbol.getName();

        return functionName;
    }

    static String getSerializeMiddlewareName(ShapeId operationShapeId, String protocol) {
        return protocol
                + "_serializeOp"
                + operationShapeId.getName();
    }

    /**
     * Context object used for service serialization and deserialization.
     */
    class GenerationContext {
        private GoSettings settings;
        private Model model;
        private ServiceShape service;
        private SymbolProvider symbolProvider;
        private GoWriter writer;
        private List<GoIntegration> integrations;
        private String protocolName;

        public GoSettings getSettings() {
            return settings;
        }

        public void setSettings(GoSettings settings) {
            this.settings = settings;
        }

        public Model getModel() {
            return model;
        }

        public void setModel(Model model) {
            this.model = model;
        }

        public ServiceShape getService() {
            return service;
        }

        public void setService(ServiceShape service) {
            this.service = service;
        }

        public SymbolProvider getSymbolProvider() {
            return symbolProvider;
        }

        public void setSymbolProvider(SymbolProvider symbolProvider) {
            this.symbolProvider = symbolProvider;
        }

        public GoWriter getWriter() {
            return writer;
        }

        public void setWriter(GoWriter writer) {
            this.writer = writer;
        }

        public List<GoIntegration> getIntegrations() {
            return integrations;
        }

        public void setIntegrations(List<GoIntegration> integrations) {
            this.integrations = integrations;
        }

        public String getProtocolName() {
            return protocolName;
        }

        public void setProtocolName(String protocolName) {
            this.protocolName = protocolName;
        }
    }
}
