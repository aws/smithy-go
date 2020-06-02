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

import java.util.Collections;
import java.util.List;
import software.amazon.smithy.codegen.core.SymbolDependency;
import software.amazon.smithy.codegen.core.SymbolDependencyContainer;

/**
 * An enum of all the built-in dependencies used by this package.
 */
public enum GoDependency implements SymbolDependencyContainer {

    // The version in the stdlib dependencies should reflect the minimum Go version.
    // The values aren't currently used, but they could potentially used to dynamically
    // set the minimum go version.
    BIG("stdlib", "", "math/big", null, Versions.GO_STDLIB),
    TIME("stdlib", "", "time", null, Versions.GO_STDLIB),
    FMT("stdlib", "", "fmt", null, Versions.GO_STDLIB),
    CONTEXT("stdlib", "", "context", null, Versions.GO_STDLIB),
    STRCONV("stdlib", "", "strconv", null, Versions.GO_STDLIB),
    BASE64("stdlib", "", "encoding/base64", null, Versions.GO_STDLIB),
    NET_URL("stdlib", "", "net/url", null, Versions.GO_STDLIB),
    NET_HTTP("stdlib", "", "net/http", null, Versions.GO_STDLIB),
    BYTES("stdlib", "", "bytes", null, Versions.GO_STDLIB),
    STRINGS("stdlib", "", "strings", null, Versions.GO_STDLIB),
    JSON("stdlib", "", "encoding/json", null, Versions.GO_STDLIB),
    IO("stdlib", "", "io", null, Versions.GO_STDLIB),
    IOUTIL("stdlib", "",  "io/ioutil", null, Versions.GO_STDLIB),

    SMITHY("dependency", "github.com/awslabs/smithy-go",
            "github.com/awslabs/smithy-go", "smithy", Versions.SMITHY_GO),
    SMITHY_HTTP_TRANSPORT("dependency", "github.com/awslabs/smithy-go",
            "github.com/awslabs/smithy-go/transport/http", "smithyhttp", Versions.SMITHY_GO),
    SMITHY_MIDDLEWARE("dependency", "github.com/awslabs/smithy-go",
            "github.com/awslabs/smithy-go/middleware", null, Versions.SMITHY_GO),
    SMITHY_TIME("dependency", "github.com/awslabs/smithy-go",
            "github.com/awslabs/smithy-go/time", "smithytime", Versions.SMITHY_GO),
    SMITHY_HTTP_BINDING("dependency", "github.com/awslabs/smithy-go",
            "github.com/awslabs/smithy-go/httpbinding", null, Versions.SMITHY_GO),
    SMITHY_JSON("dependency", "github.com/awslabs/smithy-go",
            "github.com/awslabs/smithy-go/json", "smithyjson", Versions.SMITHY_GO),

    AWS_REST_JSON_PROTOCOL("dependency", "github.com/aws/aws-sdk-go-v2",
            "github.com/aws/aws-sdk-go-v2/aws/protocol/restjson", null, Versions.AWS_SDK),
    AWS_PRIVATE_PROTOCOL("dependency", "github.com/aws/aws-sdk-go-v2",
            "github.com/aws/aws-sdk-go-v2/private/protocol", null, Versions.AWS_SDK);

    public final String sourcePath;
    public final String importPath;
    public final String alias;
    public final String version;
    public final SymbolDependency dependency;

    GoDependency(String type, String sourcePath, String importPath, String alias, String version) {
        this.dependency = SymbolDependency.builder()
                .dependencyType(type)
                .packageName(sourcePath)
                .version(version)
                .build();
        this.sourcePath = sourcePath;
        this.importPath = importPath;
        this.version = version;
        this.alias = alias;
    }

    @Override
    public List<SymbolDependency> getDependencies() {
        return Collections.singletonList(dependency);
    }

    private static final class Versions {
        private static final String GO_STDLIB = "1.14";
        private static final String SMITHY_GO = "latest";
        private static final String AWS_SDK = "v0.22.0";

    }
}
