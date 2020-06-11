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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import software.amazon.smithy.codegen.core.SymbolDependency;
import software.amazon.smithy.codegen.core.SymbolDependencyContainer;
import software.amazon.smithy.utils.SetUtils;
import software.amazon.smithy.utils.SmithyBuilder;

public final class GoDependency implements SymbolDependencyContainer, Comparable<GoDependency> {
    private final Type type;
    private final String sourcePath;
    private final String importPath;
    private final String alias;
    private final String version;
    private final Set<GoDependency> dependencies;
    private final SymbolDependency symbolDependency;

    private GoDependency(Builder builder) {
        this.type = SmithyBuilder.requiredState("type", builder.type);
        this.sourcePath = !this.type.equals(Type.STANDARD_LIBRARY)
                ? SmithyBuilder.requiredState("sourcePath", builder.sourcePath) : "";
        this.importPath = SmithyBuilder.requiredState("importPath", builder.importPath);
        this.alias = builder.alias;
        this.version = SmithyBuilder.requiredState("version", builder.version);
        this.dependencies = builder.dependencies;

        this.symbolDependency = SymbolDependency.builder()
                .dependencyType(this.type.toString())
                .packageName(this.sourcePath)
                .version(this.version)
                .build();
    }

    public Set<GoDependency> getGoDependencies() {
        return this.dependencies;
    }

    public SymbolDependency getSymbolDependency() {
        return symbolDependency;
    }

    public Type getType() {
        return type;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public String getImportPath() {
        return importPath;
    }

    public String getAlias() {
        return alias;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public List<SymbolDependency> getDependencies() {
        Set<SymbolDependency> symbolDependencySet = new TreeSet<>(SetUtils.of(getSymbolDependency()));

        symbolDependencySet.addAll(resolveDependencies(getGoDependencies(), new TreeSet<>(SetUtils.of(this))));

        return new ArrayList<>(symbolDependencySet);
    }

    private Set<SymbolDependency> resolveDependencies(Set<GoDependency> goDependencies, Set<GoDependency> processed) {
        Set<SymbolDependency> symbolDependencies = new TreeSet<>();
        if (goDependencies.size() == 0) {
            return symbolDependencies;
        }

        Set<GoDependency> dependenciesToResolve = new TreeSet<>();
        for (GoDependency dependency : goDependencies) {
            if (processed.contains(dependency)) {
                continue;
            }
            processed.add(dependency);
            symbolDependencies.add(dependency.getSymbolDependency());
            dependenciesToResolve.addAll(dependency.getGoDependencies());
        }

        symbolDependencies.addAll(resolveDependencies(dependenciesToResolve, processed));

        return symbolDependencies;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        } else if (!(o instanceof GoDependency)) {
            return false;
        }

        GoDependency other = (GoDependency) o;

        return this.type.equals(other.type) && this.sourcePath.equals(other.sourcePath)
                && this.importPath.equals(other.importPath) && this.alias.equals(other.alias)
                && this.version.equals(other.version) && this.dependencies.equals(other.dependencies);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, sourcePath, importPath, alias, version, dependencies);
    }

    @Override
    public int compareTo(GoDependency o) {
        if (equals(o)) {
            return 0;
        }

        int importPathCompare = importPath.compareTo(o.importPath);
        if (importPathCompare != 0) {
            return importPathCompare;
        }

        if (alias != null) {
            return alias.compareTo(o.alias);
        }

        return version.compareTo(o.version);
    }

    public enum Type {
        STANDARD_LIBRARY, DEPENDENCY;

        @Override
        public String toString() {
            switch (this) {
                case STANDARD_LIBRARY:
                    return "stdlib";
                case DEPENDENCY:
                    return "dependency";
                default:
                    return "unknown";
            }
        }
    }

    public static GoDependency moduleDependency(String sourcePath, String importPath, String version, String alias) {
        GoDependency.Builder builder = GoDependency.builder()
                .type(GoDependency.Type.DEPENDENCY)
                .sourcePath(sourcePath)
                .importPath(importPath)
                .version(version);
        if (alias != null) {
            builder.alias(alias);
        }
        return builder.build();
    }

    public static GoDependency standardLibraryDependency(String importPath, String version) {
        return GoDependency.builder()
                .type(GoDependency.Type.STANDARD_LIBRARY)
                .importPath(importPath)
                .version(version)
                .build();
    }

    public static final class Builder implements SmithyBuilder<GoDependency> {
        private Type type;
        private String sourcePath;
        private String importPath;
        private String alias;
        private String version;
        private final Set<GoDependency> dependencies = new TreeSet<>();

        private Builder() {
        }

        public Builder type(Type type) {
            this.type = type;
            return this;
        }

        public Builder sourcePath(String sourcePath) {
            this.sourcePath = sourcePath;
            return this;
        }

        public Builder importPath(String importPath) {
            this.importPath = importPath;
            return this;
        }

        public Builder alias(String alias) {
            this.alias = alias;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder dependencies(Collection<GoDependency> dependencies) {
            this.dependencies.clear();
            this.dependencies.addAll(dependencies);
            return this;
        }

        public Builder addDependency(GoDependency dependency) {
            this.dependencies.add(dependency);
            return this;
        }

        public Builder removeDependency(GoDependency dependency) {
            this.dependencies.remove(dependency);
            return this;
        }

        @Override
        public GoDependency build() {
            return new GoDependency(this);
        }
    }
}
