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
    BIG("stdlib", "", "math/big", null, "1.14"),
    TIME("stdlib", "", "time", null, "1.14"),
    FMT("stdlib", "", "fmt", null, "1.14"),
    CONTEXT("stdlib", "", "context", null, "1.14"),
    STRCONV("stdlib", "", "strconv", null, "1.14"),
    BASE64ENCODING("stdlib", "", "encoding/base64", null, "1.14"),

    SMITHY("dependency", "github.com/awslabs/smithy-go", "github.com/awslabs/smithy-go",
            "smithy", "v0.0.1"),
    SMITHY_HTTP_TRANSPORT("dependency", "github.com/awslabs/smithy-go",
            "github.com/awslabs/smithy-go/transport/http", "smithyhttp", "v0.0.1"),
    SMITHY_MIDDLEWARE("dependency", "github.com/awslabs/smithy-go",
            "github.com/awslabs/smithy-go/middleware", null, "v0.0.1"),

    AWS_REST_PROTOCOL("dependency", "github.com/aws/aws-sdk-go-v2",
            "github.com/aws/aws-sdk-go-v2/aws/protocol/rest", null, "v0.22.0"),
    AWS_PRIVATE_PROTOCOL("dependency", "github.com/aws/aws-sdk-go-v2",
            "github.com/aws/aws-sdk-go-v2/aws/private/protocol", null, "v0.22.0");

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
}
