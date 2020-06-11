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

/**
 * An enum of all the built-in dependencies used by this package.
 */
public enum SmithyGoDependency {

    // The version in the stdlib dependencies should reflect the minimum Go version.
    // The values aren't currently used, but they could potentially used to dynamically
    // set the minimum go version.
    BIG(stdlib("math/big")),
    TIME(stdlib("time")),
    FMT(stdlib("fmt")),
    CONTEXT(stdlib("context")),
    STRCONV(stdlib("strconv")),
    BASE64(stdlib("encoding/base64")),
    NET_URL(stdlib("net/url")),
    NET_HTTP(stdlib("net/http")),
    BYTES(stdlib("bytes")),
    STRINGS(stdlib("strings")),
    JSON(stdlib("encoding/json")),
    IO(stdlib("io")),
    IOUTIL(stdlib("io/ioutil")),

    SMITHY(smithy(null, "smithy")),
    SMITHY_HTTP_TRANSPORT(smithy("transport/http", "smithyhttp")),
    SMITHY_MIDDLEWARE(smithy("middleware")),
    SMITHY_TIME(smithy("time", "smithytime")),
    SMITHY_HTTP_BINDING(smithy("httpbinding")),
    SMITHY_JSON(smithy("json", "smithyjson")),
    SMITHY_IO(smithy("io", "smithyio")),
    SMITHY_PTR(smithy("ptr"));

    private static final String SMITHY_SOURCE_PATH = "github.com/awslabs/smithy-go";

    private final GoDependency dependency;

    SmithyGoDependency(GoDependency dependency) {
        this.dependency = dependency;
    }

    public GoDependency getDependency() {
        return dependency;
    }

    private static GoDependency stdlib(String importPath) {
        return GoDependency.standardLibraryDependency(importPath, Versions.GO_STDLIB);
    }

    private static GoDependency smithy(String relativePath) {
        return smithy(relativePath, null);
    }

    private static GoDependency smithy(String relativePath, String alias) {
        String importPath = SMITHY_SOURCE_PATH;
        if (relativePath != null) {
            importPath = importPath + "/" + relativePath;
        }
        return GoDependency.moduleDependency(SMITHY_SOURCE_PATH, importPath, Versions.SMITHY_GO, alias);
    }

    private static final class Versions {
        private static final String GO_STDLIB = "1.14";
        private static final String SMITHY_GO = "v0.0.0-20200604194311-25e885347bc8";
    }
}
