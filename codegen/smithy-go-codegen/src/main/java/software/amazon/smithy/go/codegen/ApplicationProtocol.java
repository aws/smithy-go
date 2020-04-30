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

import java.util.Objects;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolReference;
import software.amazon.smithy.utils.ListUtils;


/**
 * Represents the resolves {@link Symbol}s and references for an
 * application protocol (e.g., "http", "mqtt", etc).
 */
public final class ApplicationProtocol {

    private final String name;
    private final SymbolReference requestType;
    private final SymbolReference responseType;

    /**
     * Creates a resolved application protocol.
     *
     * @param name The protocol name (e.g., http, mqtt, etc).
     * @param requestType The type used to represent request messages for the protocol.
     * @param responseType The type used to represent response messages for the protocol.
     */
    public ApplicationProtocol(
            String name,
            SymbolReference requestType,
            SymbolReference responseType
    ) {
        this.name = name;
        this.requestType = requestType;
        this.responseType = responseType;
    }

    /**
     * Creates a default HTTP application protocol.
     *
     * @return Returns the created application protocol.
     */
    public static ApplicationProtocol createDefaultHttpApplicationProtocol() {
        return new ApplicationProtocol(
                "http",
                SymbolReference.builder()
                        .symbol(createHttpSymbol(GoDependency.SMITHY_HTTP_TRANSPORT, "http.Request"))
                        .build(),
                SymbolReference.builder()
                        .symbol(createHttpSymbol(GoDependency.SMITHY_HTTP_TRANSPORT, "http.Response"))
                        .build()
        );
    }

    private static Symbol createHttpSymbol(GoDependency dependency, String symbolName) {
        SymbolReference symbolReference = SymbolUtils.createNamespaceReference(dependency);
        return SymbolUtils.createPointableSymbolBuilder(symbolName)
                .references(ListUtils.of(symbolReference))
                .build();
    }

    /**
     * Gets the protocol name.
     *
     * <p>All HTTP protocols should start with "http".
     * All MQTT protocols should start with "mqtt".
     *
     * @return Returns the protocol name.
     */
    public String getName() {
        return name;
    }

    /**
     * Checks if the protocol is an HTTP based protocol.
     *
     * @return Returns true if it is HTTP based.
     */
    public boolean isHttpProtocol() {
        return getName().startsWith("http");
    }

    /**
     * Gets the symbol used to refer to the request type for this protocol.
     *
     * @return Returns the protocol request type.
     */
    public SymbolReference getRequestType() {
        return requestType;
    }

    /**
     * Gets the symbol used to refer to the response type for this protocol.
     *
     * @return Returns the protocol response type.
     */
    public SymbolReference getResponseType() {
        return responseType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        } else if (!(o instanceof ApplicationProtocol)) {
            return false;
        }

        ApplicationProtocol that = (ApplicationProtocol) o;
        return requestType.equals(that.requestType)
               && responseType.equals(that.responseType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requestType, responseType);
    }
}
