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
    }

    public static final class Ptr {
        public static final Symbol String = SmithyGoDependency.SMITHY_PTR.valueSymbol("String");
        public static final Symbol Bool = SmithyGoDependency.SMITHY_PTR.valueSymbol("Bool");
    }

    public static final class Middleware {
        public static final Symbol Stack = SmithyGoDependency.SMITHY_MIDDLEWARE.pointableSymbol("Stack");
        public static final Symbol SerializeInput = SmithyGoDependency.SMITHY_MIDDLEWARE.pointableSymbol("SerializeInput");
        public static final Symbol SerializeOutput = SmithyGoDependency.SMITHY_MIDDLEWARE.pointableSymbol("SerializeOutput");
        public static final Symbol SerializeHandler = SmithyGoDependency.SMITHY_MIDDLEWARE.pointableSymbol("SerializeHandler");
        public static final Symbol Metadata = SmithyGoDependency.SMITHY_MIDDLEWARE.pointableSymbol("Metadata");
    }

    public static final class Transport {
        public static final class Http {
            public static final Symbol Request = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.pointableSymbol("Request");

            public static final Symbol NewSigV4Option = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("NewSigV4Option");
            public static final Symbol NewSigV4AOption = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("NewSigV4AOption");
            public static final Symbol NewBearerOption = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("NewBearerOption");
            public static final Symbol NewAnonymousOption = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.valueSymbol("NewAnonymousOption");

            public static final Symbol SigV4Properties = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.pointableSymbol("SigV4Properties");
            public static final Symbol SigV4AProperties = SmithyGoDependency.SMITHY_HTTP_TRANSPORT.pointableSymbol("SigV4AProperties");
        }
    }

    public static final class Auth {
        public static final Symbol Option = SmithyGoDependency.SMITHY_AUTH.pointableSymbol("Option");
    }
}
