/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
 *
 *
 */

package software.amazon.smithy.go.codegen.integration;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SmithyGoDependency.SMITHY_MIDDLEWARE;
import static software.amazon.smithy.go.codegen.SmithyGoDependency.SMITHY_TRACING;
import static software.amazon.smithy.go.codegen.SymbolUtils.buildPackageSymbol;

import java.util.List;
import java.util.Map;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.GoWriter;
import software.amazon.smithy.go.codegen.middleware.BuildStepMiddleware;
import software.amazon.smithy.go.codegen.middleware.InitializeStepMiddleware;
import software.amazon.smithy.go.codegen.middleware.SerializeStepMiddleware;

/**
 * Instruments the client with various base trace spans.
 */
public class TracingSpans implements GoIntegration {
    public static final MiddlewareRegistrar SPAN_INITIALIZE_START = MiddlewareRegistrar.builder()
            .resolvedFunction(buildPackageSymbol("addSpanInitializeStart"))
            .build();
    public static final MiddlewareRegistrar SPAN_INITIALIZE_END = MiddlewareRegistrar.builder()
            .resolvedFunction(buildPackageSymbol("addSpanInitializeEnd"))
            .build();
    public static final MiddlewareRegistrar SPAN_BUILD_REQUEST_START = MiddlewareRegistrar.builder()
            .resolvedFunction(buildPackageSymbol("addSpanBuildRequestStart"))
            .build();
    public static final MiddlewareRegistrar SPAN_BUILD_REQUEST_END = MiddlewareRegistrar.builder()
            .resolvedFunction(buildPackageSymbol("addSpanBuildRequestEnd"))
            .build();

    @Override
    public byte getOrder() {
        return 127;
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return List.of(
                RuntimeClientPlugin.builder().registerMiddleware(SPAN_INITIALIZE_START).build(),
                RuntimeClientPlugin.builder().registerMiddleware(SPAN_INITIALIZE_END).build(),
                RuntimeClientPlugin.builder().registerMiddleware(SPAN_BUILD_REQUEST_START).build(),
                RuntimeClientPlugin.builder().registerMiddleware(SPAN_BUILD_REQUEST_END).build()
        );
    }

    @Override
    public void writeAdditionalFiles(GoCodegenContext ctx) {
        ctx.writerDelegator().useFileWriter("api_client.go", ctx.settings().getModuleName(), goTemplate("""
                $initializeStart:W
                $initializeEnd:W

                $buildRequestStart:W
                $buildRequestEnd:W

                func addSpanInitializeStart(stack $stack:P) error {
                    return stack.Initialize.Add(&spanInitializeStart{}, $before:T)
                }

                func addSpanInitializeEnd(stack $stack:P) error {
                    return stack.Initialize.Add(&spanInitializeEnd{}, $after:T)
                }

                func addSpanBuildRequestStart(stack $stack:P) error {
                    return stack.Serialize.Add(&spanBuildRequestStart{}, $before:T)
                }

                func addSpanBuildRequestEnd(stack $stack:P) error {
                    return stack.Build.Add(&spanBuildRequestEnd{}, $after:T)
                }
                """,
                Map.of(
                        "initializeStart", new SpanInitializeStart(),
                        "initializeEnd", new SpanInitializeEnd(),
                        "buildRequestStart", new SpanBuildRequestStart(),
                        "buildRequestEnd", new SpanBuildRequestEnd(),
                        "stack", SMITHY_MIDDLEWARE.struct("Stack"),
                        "before", SMITHY_MIDDLEWARE.constSymbol("Before"),
                        "after", SMITHY_MIDDLEWARE.constSymbol("After")
                )));
    }

    private static final class SpanInitializeStart extends InitializeStepMiddleware {
        @Override
        public String getStructName() {
            return "spanInitializeStart";
        }

        @Override
        public GoWriter.Writable getFuncBody() {
            return goTemplate("""
                    ctx, _ = $T(ctx, "Initialize")

                    return next.HandleInitialize(ctx, in)
                    """, SMITHY_TRACING.func("StartSpan"));
        }
    }

    private static final class SpanInitializeEnd extends InitializeStepMiddleware {
        @Override
        public String getStructName() {
            return "spanInitializeEnd";
        }

        @Override
        public GoWriter.Writable getFuncBody() {
            return goTemplate("""
                    ctx, span := $T(ctx)
                    span.End()

                    return next.HandleInitialize(ctx, in)
                    """, SMITHY_TRACING.func("PopSpan"));
        }
    }

    private static final class SpanBuildRequestStart extends SerializeStepMiddleware {
        @Override
        public String getStructName() {
            return "spanBuildRequestStart";
        }

        @Override
        public GoWriter.Writable getFuncBody() {
            return goTemplate("""
                    ctx, _ = $T(ctx, "BuildRequest")

                    return next.HandleSerialize(ctx, in)
                    """, SMITHY_TRACING.func("StartSpan"));
        }
    }

    private static final class SpanBuildRequestEnd extends BuildStepMiddleware {
        @Override
        public String getStructName() {
            return "spanBuildRequestEnd";
        }

        @Override
        public GoWriter.Writable getFuncBody() {
            return goTemplate("""
                    ctx, span := $T(ctx)
                    span.End()

                    return next.HandleBuild(ctx, in)
                    """, SMITHY_TRACING.func("PopSpan"));
        }
    }
}
