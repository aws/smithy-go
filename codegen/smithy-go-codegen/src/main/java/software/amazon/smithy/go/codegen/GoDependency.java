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
    BIG("stdlib", "math/big", "1.14"),
    IO("stdlib", "io", "1.14"),
    TIME("stdlib", "time", "1.14");

    public final String packageName;
    public final String version;
    public final SymbolDependency dependency;

    GoDependency(String type, String name, String version) {
        this.dependency = SymbolDependency.builder()
                .dependencyType(type)
                .packageName(name)
                .version(version)
                .build();
        this.packageName = name;
        this.version = version;
    }

    @Override
    public List<SymbolDependency> getDependencies() {
        return Collections.singletonList(dependency);
    }
}
