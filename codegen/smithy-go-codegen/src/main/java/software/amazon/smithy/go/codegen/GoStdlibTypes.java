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
 * Collection of Symbol constants for types in the go standard library.
 */
@SuppressWarnings({"checkstyle:ConstantName", "checkstyle:LineLength"})
public final class GoStdlibTypes {
    private GoStdlibTypes() { }

    public static final class Context {
        public static final Symbol Context = SmithyGoDependency.CONTEXT.valueSymbol("Context");
        public static final Symbol Background = SmithyGoDependency.CONTEXT.valueSymbol("Background");
    }

    public static final class Fmt {
        public static final Symbol Errorf = SmithyGoDependency.FMT.valueSymbol("Errorf");
    }

    public static final class Net {
        public static final class Http {
            public static final Symbol Request = SmithyGoDependency.NET_HTTP.pointableSymbol("Request");
            public static final Symbol Response = SmithyGoDependency.NET_HTTP.pointableSymbol("Response");
        }
    }

    public static final class Path {
        public static final Symbol Join = SmithyGoDependency.PATH.valueSymbol("Join");
    }

    public static final class Testing {
        public static final Symbol T = SmithyGoDependency.TESTING.pointableSymbol("T");
    }
}
