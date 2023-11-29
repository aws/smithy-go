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

/**
 * Collection of Symbol constants for types in the smithy-go runtime.
 */
@SuppressWarnings({"checkstyle:ConstantName", "checkstyle:LineLength"})
public final class SmithyGoTypes {
    private SmithyGoTypes() { }

    public static final class Smithy {
        public static final Symbol Properties = SmithyGoDependency.SMITHY.pointableSymbol("Properties");
        public static final Symbol OperationError = SmithyGoDependency.SMITHY.pointableSymbol("OperationError");
    }

    public static final class Ptr {
        public static final Symbol String = SmithyGoDependency.SMITHY_PTR.valueSymbol("String");
        public static final Symbol Bool = SmithyGoDependency.SMITHY_PTR.valueSymbol("Bool");
    }

    public static final class Middleware {
        public static final Symbol Stack = SmithyGoDependency.SMITHY_MIDDLEWARE.pointableSymbol("Stack");
        public static final Symbol NewStack = SmithyGoDependency.SMITHY_MIDDLEWARE.valueSymbol("NewStack");
        public static final Symbol Metadata = SmithyGoDependency.SMITHY_MIDDLEWARE.pointableSymbol("Metadata");
        public static final Symbol ClearStackValues = SmithyGoDependency.SMITHY_MIDDLEWARE.valueSymbol("ClearStackValues");
        public static final Symbol WithStackValue = SmithyGoDependency.SMITHY_MIDDLEWARE.valueSymbol("WithStackValue");
        public static final Symbol GetStackValue = SmithyGoDependency.SMITHY_MIDDLEWARE.valueSymbol("GetStackValue");
        public static final Symbol After = SmithyGoDependency.SMITHY_MIDDLEWARE.valueSymbol("After");
        public static final Symbol Before = SmithyGoDependency.SMITHY_MIDDLEWARE.valueSymbol("Before");
        public static final Symbol DecorateHandler = SmithyGoDependency.SMITHY_MIDDLEWARE.valueSymbol("DecorateHandler");

        public static final Symbol InitializeInput = SmithyGoDependency.SMITHY_MIDDLEWARE.pointableSymbol("InitializeInput");
        public static final Symbol InitializeOutput = SmithyGoDependency.SMITHY_MIDDLEWARE.pointableSymbol("InitializeOutput");
        public static final Symbol InitializeHandler = SmithyGoDependency.SMITHY_MIDDLEWARE.pointableSymbol("InitializeHandler");
        public static final Symbol SerializeInput = SmithyGoDependency.SMITHY_MIDDLEWARE.pointableSymbol("SerializeInput");
        public static final Symbol SerializeOutput = SmithyGoDependency.SMITHY_MIDDLEWARE.pointableSymbol("SerializeOutput");
        public static final Symbol SerializeHandler = SmithyGoDependency.SMITHY_MIDDLEWARE.pointableSymbol("SerializeHandler");
        public static final Symbol FinalizeInput = SmithyGoDependency.SMITHY_MIDDLEWARE.pointableSymbol("FinalizeInput");
        public static final Symbol FinalizeOutput = SmithyGoDependency.SMITHY_MIDDLEWARE.pointableSymbol("FinalizeOutput");
        public static final Symbol FinalizeHandler = SmithyGoDependency.SMITHY_MIDDLEWARE.pointableSymbol("FinalizeHandler");
    }

    public static final class Transport {
        public static final class Http {
            public static final Symbol Request = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.pointableSymbol("Request");
            public static final Symbol NewStackRequest = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("NewStackRequest");
            public static final Symbol NewClientHandler = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("NewClientHandler");

            public static final Symbol JoinPath = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("JoinPath");

            public static final Symbol GetHostnameImmutable = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("GetHostnameImmutable");

            public static final Symbol AuthScheme = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("AuthScheme");
            public static final Symbol NewAnonymousScheme = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("NewAnonymousScheme");

            public static final Symbol GetSigV4SigningName = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("GetSigV4SigningName");
            public static final Symbol GetSigV4SigningRegion = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("GetSigV4SigningRegion");
            public static final Symbol GetSigV4ASigningName = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("GetSigV4ASigningName");
            public static final Symbol GetSigV4ASigningRegions = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("GetSigV4ASigningRegions");

            public static final Symbol SetSigV4SigningName = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("SetSigV4SigningName");
            public static final Symbol SetSigV4SigningRegion = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("SetSigV4SigningRegion");
            public static final Symbol SetSigV4ASigningName = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("SetSigV4ASigningName");
            public static final Symbol SetSigV4ASigningRegions = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("SetSigV4ASigningRegions");
            public static final Symbol SetIsUnsignedPayload = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("SetIsUnsignedPayload");
            public static final Symbol SetDisableDoubleEncoding = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("SetDisableDoubleEncoding");
        }
    }

    public static final class Auth {
        public static final Symbol Option = SmithyGoDependency.SMITHY_AUTH.pointableSymbol("Option");
        public static final Symbol IdentityResolver = SmithyGoDependency.SMITHY_AUTH.valueSymbol("IdentityResolver");
        public static final Symbol Identity = SmithyGoDependency.SMITHY_AUTH.valueSymbol("Identity");
        public static final Symbol AnonymousIdentityResolver = SmithyGoDependency.SMITHY_AUTH.pointableSymbol("AnonymousIdentityResolver");
        public static final Symbol GetAuthOptions = SmithyGoDependency.SMITHY_AUTH.valueSymbol("GetAuthOptions");
        public static final Symbol SetAuthOptions = SmithyGoDependency.SMITHY_AUTH.valueSymbol("SetAuthOptions");

        public static final Symbol SchemeIDAnonymous = SmithyGoDependency.SMITHY_AUTH.valueSymbol("SchemeIDAnonymous");
        public static final Symbol SchemeIDHTTPBasic = SmithyGoDependency.SMITHY_AUTH.valueSymbol("SchemeIDHTTPBasic");
        public static final Symbol SchemeIDHTTPDigest = SmithyGoDependency.SMITHY_AUTH.valueSymbol("SchemeIDHTTPDigest");
        public static final Symbol SchemeIDHTTPBearer = SmithyGoDependency.SMITHY_AUTH.valueSymbol("SchemeIDHTTPBearer");
        public static final Symbol SchemeIDHTTPAPIKey = SmithyGoDependency.SMITHY_AUTH.valueSymbol("SchemeIDHTTPAPIKey");
        public static final Symbol SchemeIDSigV4 = SmithyGoDependency.SMITHY_AUTH.valueSymbol("SchemeIDSigV4");
        public static final Symbol SchemeIDSigV4A = SmithyGoDependency.SMITHY_AUTH.valueSymbol("SchemeIDSigV4A");

        public static final class Bearer {
            public static final Symbol TokenProvider = SmithyGoDependency.SMITHY_AUTH_BEARER.valueSymbol("TokenProvider");
            public static final Symbol Signer = SmithyGoDependency.SMITHY_AUTH_BEARER.valueSymbol("Signer");
            public static final Symbol NewSignHTTPSMessage = SmithyGoDependency.SMITHY_AUTH_BEARER.valueSymbol("NewSignHTTPSMessage");
        }
    }

    public static final class Private {
        public static final class RequestCompression {
            public static final Symbol AddRequestCompression = SmithyGoDependency.SMITHY_REQUEST_COMPRESSION.valueSymbol("AddRequestCompression");
        }
    }
}
