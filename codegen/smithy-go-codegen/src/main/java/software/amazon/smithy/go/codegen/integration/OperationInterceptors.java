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
 */

package software.amazon.smithy.go.codegen.integration;

import static software.amazon.smithy.go.codegen.SymbolUtils.buildPackageSymbol;

import java.util.List;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.SmithyGoDependency;

// This DOES NOT add retry interceptors, because pure Smithy clients don't have a retry loop right now.
public class OperationInterceptors implements GoIntegration {
    private static RuntimeClientPlugin interceptor(String name) {
        return RuntimeClientPlugin.builder()
                .registerMiddleware(
                        MiddlewareRegistrar.builder()
                                .resolvedFunction(buildPackageSymbol(name))
                                .useClientOptions()
                                .build()
                )
                .build();
    }

    @Override
    public byte getOrder() {
        return 127;
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return List.of(
                interceptor("addInterceptExecution"),
                interceptor("addInterceptBeforeSerialization"),
                interceptor("addInterceptAfterSerialization"),
                interceptor("addInterceptBeforeSigning"),
                interceptor("addInterceptAfterSigning"),
                interceptor("addInterceptTransmit"),
                interceptor("addInterceptBeforeDeserialization"),
                interceptor("addInterceptAfterDeserialization")
        );
    }

    @Override
    public void writeAdditionalFiles(GoCodegenContext ctx) {
        ctx.writerDelegator().useFileWriter("api_client.go", ctx.settings().getModuleName(), writer -> {
            writer.addUseImports(SmithyGoDependency.SMITHY_MIDDLEWARE);
            writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_TRANSPORT);
            writer.write("""
                    func addInterceptExecution(stack *middleware.Stack, opts Options) error {
                        return stack.Initialize.Add(&smithyhttp.InterceptExecution{
                            BeforeExecution: opts.Interceptors.BeforeExecution,
                            AfterExecution:  opts.Interceptors.AfterExecution,
                        }, middleware.Before)
                    }

                    func addInterceptBeforeSerialization(stack *middleware.Stack, opts Options) error {
                        return stack.Serialize.Insert(&smithyhttp.InterceptBeforeSerialization{
                            Interceptors: opts.Interceptors.BeforeSerialization,
                        }, "OperationSerializer", middleware.Before)
                    }

                    func addInterceptAfterSerialization(stack *middleware.Stack, opts Options) error {
                        return stack.Serialize.Insert(&smithyhttp.InterceptAfterSerialization{
                            Interceptors: opts.Interceptors.AfterSerialization,
                        }, "OperationSerializer", middleware.After)
                    }

                    func addInterceptBeforeSigning(stack *middleware.Stack, opts Options) error {
                        return stack.Finalize.Insert(&smithyhttp.InterceptBeforeSigning{
                            Interceptors: opts.Interceptors.BeforeSigning,
                        }, "Signing", middleware.Before)
                    }

                    func addInterceptAfterSigning(stack *middleware.Stack, opts Options) error {
                        return stack.Finalize.Insert(&smithyhttp.InterceptAfterSigning{
                            Interceptors: opts.Interceptors.AfterSigning,
                        }, "Signing", middleware.After)
                    }

                    func addInterceptTransmit(stack *middleware.Stack, opts Options) error {
                        return stack.Deserialize.Add(&smithyhttp.InterceptTransmit{
                            BeforeTransmit: opts.Interceptors.BeforeTransmit,
                            AfterTransmit:  opts.Interceptors.AfterTransmit,
                        }, middleware.After)
                    }

                    func addInterceptBeforeDeserialization(stack *middleware.Stack, opts Options) error {
                        return stack.Deserialize.Insert(&smithyhttp.InterceptBeforeDeserialization{
                            Interceptors: opts.Interceptors.BeforeDeserialization,
                        }, "OperationDeserializer", middleware.After) // (deserialize stack is called in reverse)
                    }

                    func addInterceptAfterDeserialization(stack *middleware.Stack, opts Options) error {
                        return stack.Deserialize.Insert(&smithyhttp.InterceptAfterDeserialization{
                            Interceptors: opts.Interceptors.AfterDeserialization,
                        }, "OperationDeserializer", middleware.Before)
                    }
                    """);
        });
    }
}
