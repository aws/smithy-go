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

package software.amazon.smithy.go.codegen;

import static software.amazon.smithy.go.codegen.GoWriter.autoDocTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goDocTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.auth.AuthSchemeResolverGenerator;
import software.amazon.smithy.go.codegen.auth.GetIdentityMiddlewareGenerator;
import software.amazon.smithy.go.codegen.auth.ResolveAuthSchemeMiddlewareGenerator;
import software.amazon.smithy.go.codegen.auth.SignRequestMiddlewareGenerator;
import software.amazon.smithy.go.codegen.endpoints.EndpointMiddlewareGenerator;
import software.amazon.smithy.go.codegen.integration.AuthSchemeDefinition;
import software.amazon.smithy.go.codegen.integration.ClientMember;
import software.amazon.smithy.go.codegen.integration.ClientMemberResolver;
import software.amazon.smithy.go.codegen.integration.ConfigFieldResolver;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.go.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.ServiceIndex;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.utils.MapUtils;

/**
 * Generates a service client, its constructors, and core supporting logic.
 */
final class ServiceGenerator implements Runnable {

    public static final String CONFIG_NAME = "Options";

    private final GoSettings settings;
    private final Model model;
    private final SymbolProvider symbolProvider;
    private final GoWriter writer;
    private final ServiceShape service;
    private final List<GoIntegration> integrations;
    private final List<RuntimeClientPlugin> runtimePlugins;
    private final ApplicationProtocol applicationProtocol;
    private final Map<ShapeId, AuthSchemeDefinition> authSchemes;

    ServiceGenerator(
            GoSettings settings,
            Model model,
            SymbolProvider symbolProvider,
            GoWriter writer,
            ServiceShape service,
            List<GoIntegration> integrations,
            List<RuntimeClientPlugin> runtimePlugins,
            ApplicationProtocol applicationProtocol
    ) {
        this.settings = settings;
        this.model = model;
        this.symbolProvider = symbolProvider;
        this.writer = writer;
        this.service = service;
        this.integrations = integrations;
        this.runtimePlugins = runtimePlugins;
        this.applicationProtocol = applicationProtocol;
        this.authSchemes = integrations.stream()
                .flatMap(it -> it.getClientPlugins(model, service).stream())
                .flatMap(it -> it.getAuthSchemeDefinitions().entrySet().stream())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public void run() {
        writer.write(generate());
        writeProtocolResolverImpls();
    }

    private GoWriter.Writable generate() {
        return GoWriter.ChainWritable.of(
                generateMetadata(),
                generateClient(),
                generateNew(),
                generateGetOptions(),
                generateInvokeOperation(),
                generateInputContextFuncs(),
                generateAddProtocolFinalizerMiddleware()
        ).compose();
    }

    private GoWriter.Writable generateMetadata() {
        var serviceId = settings.getService().toString();
        for (var integration : integrations) {
            serviceId = integration.processServiceId(settings, model, serviceId);
        }

        return goTemplate("""
                const ServiceID = $S
                const ServiceAPIVersion = $S
                """, serviceId, service.getVersion());
    }

    private GoWriter.Writable generateClient() {
        return goTemplate("""
                $W
                type $T struct {
                    options $L

                    $W
                }
                """,
                generateClientDocs(),
                symbolProvider.toSymbol(service),
                CONFIG_NAME,
                GoWriter.ChainWritable.of(
                        getAllClientMembers().stream()
                                .map(this::generateClientMember)
                                .toList()
                ).compose());
    }

    private GoWriter.Writable generateClientDocs() {
        return writer ->
            writer.writeDocs(String.format(
                    "%s provides the API client to make operations call for %s.",
                    symbolProvider.toSymbol(service).getName(),
                    CodegenUtils.getServiceTitle(service, "the API")
            ));
    }

    private GoWriter.Writable generateClientMember(ClientMember member) {
        return goTemplate("""
                $W
                $L $P
                """,
                member.getDocumentation().isPresent()
                        ? goDocTemplate(member.getDocumentation().get())
                        : emptyGoTemplate(),
                member.getName(),
                member.getType());
    }

    private GoWriter.Writable generateNew() {
        var plugins = runtimePlugins.stream()
                .filter(it -> it.matchesService(model, service))
                .toList();
        var serviceSymbol = symbolProvider.toSymbol(service);
        var docs = goDocTemplate(
                "New returns an initialized $name:L based on the functional options. Provide "
                + "additional functional options to further configure the behavior of the client, such as changing the "
                + "client's endpoint or adding custom middleware behavior.",
                MapUtils.of("name", serviceSymbol.getName()));
        return goTemplate("""
                $docs:W
                func New(options $options:L, optFns ...func(*$options:L)) *$client:L {
                    options = options.Copy()

                    $resolvers:W

                    $protocolResolvers:W

                    for _, fn := range optFns {
                        fn(&options)
                    }

                    $finalizers:W

                    $protocolFinalizers:W

                    client := &$client:L{
                        options: options,
                    }

                    $withClientFinalizers:W

                    $clientMemberResolvers:W

                    return client
                }
                """, MapUtils.of(
                        "docs", docs,
                        "options", CONFIG_NAME,
                        "client", serviceSymbol.getName(),
                        "protocolResolvers", generateProtocolResolvers(),
                        "protocolFinalizers", generateProtocolFinalizers(),
                        "resolvers", GoWriter.ChainWritable.of(
                                getConfigResolvers(
                                        ConfigFieldResolver.Location.CLIENT,
                                        ConfigFieldResolver.Target.INITIALIZATION
                                ).map(this::generateConfigFieldResolver).toList()
                        ).compose(),
                        "finalizers", GoWriter.ChainWritable.of(
                                getConfigResolvers(
                                        ConfigFieldResolver.Location.CLIENT,
                                        ConfigFieldResolver.Target.FINALIZATION
                                ).map(this::generateConfigFieldResolver).toList()
                        ).compose(),
                        "withClientFinalizers", GoWriter.ChainWritable.of(
                                getConfigResolvers(
                                        ConfigFieldResolver.Location.CLIENT,
                                        ConfigFieldResolver.Target.FINALIZATION_WITH_CLIENT
                                ).map(this::generateConfigFieldResolver).toList()
                        ).compose(),
                        "clientMemberResolvers", GoWriter.ChainWritable.of(
                                plugins.stream()
                                        .flatMap(it -> it.getClientMemberResolvers().stream())
                                        .map(this::generateClientMemberResolver)
                                        .toList()
                        ).compose()
                ));
    }

    private GoWriter.Writable generateGetOptions() {
        var docs = autoDocTemplate("""
                Options returns a copy of the client configuration.

                Callers SHOULD NOT perform mutations on any inner structures within client config. Config overrides
                should instead be made on a per-operation basis through functional options.""");
        return goTemplate("""
                $W
                func (c $P) Options() $L {
                    return c.options.Copy()
                }
                """, docs, symbolProvider.toSymbol(service), ClientOptions.NAME);
    }

    private GoWriter.Writable generateConfigFieldResolver(ConfigFieldResolver resolver) {
        return writer -> {
                writer.writeInline("$T(&options", resolver.getResolver());
                if (resolver.isWithOperationName()) {
                    writer.writeInline(", opID");
                }
                if (resolver.isWithClientInput()) {
                    if (resolver.getLocation() == ConfigFieldResolver.Location.CLIENT) {
                        writer.writeInline(", client");
                    } else {
                        writer.writeInline(", *c");
                    }
                }
                writer.write(")");
        };
    }

    private GoWriter.Writable generateClientMemberResolver(ClientMemberResolver resolver) {
        return goTemplate("$T(client)", resolver.getResolver());
    }

    private List<ClientMember> getAllClientMembers() {
        List<ClientMember> clientMembers = new ArrayList<>();
        for (RuntimeClientPlugin runtimeClientPlugin : runtimePlugins) {
            if (!runtimeClientPlugin.matchesService(model, service)) {
                continue;
            }

            clientMembers.addAll(runtimeClientPlugin.getClientMembers());
        }
        return clientMembers.stream()
                .distinct()
                .sorted(Comparator.comparing(ClientMember::getName))
                .collect(Collectors.toList());
    }

    private GoWriter.Writable generateProtocolResolvers() {
        ensureSupportedProtocol();
        return goTemplate("""
                resolveAuthSchemeResolver(&options)
                """);
    }

    private GoWriter.Writable generateProtocolFinalizers() {
        ensureSupportedProtocol();
        return goTemplate("""
                resolveAuthSchemes(&options)
                """);
    }

    private void writeProtocolResolverImpls() {
        ensureSupportedProtocol();

        var schemeMappings = GoWriter.ChainWritable.of(
                ServiceIndex.of(model)
                        .getEffectiveAuthSchemes(service).keySet().stream()
                        .filter(authSchemes::containsKey)
                        .map(authSchemes::get)
                        .map(it -> goTemplate("$W, ", it.generateDefaultAuthScheme()))
                        .toList()
        ).compose(false);

        writer.write("""
                func resolveAuthSchemeResolver(options *Options) {
                    if options.AuthSchemeResolver == nil {
                        options.AuthSchemeResolver = &$L{}
                    }
                }

                func resolveAuthSchemes(options *Options) {
                    if options.AuthSchemes == nil {
                        options.AuthSchemes = []$T{
                            $W
                        }
                    }
                }
                """,
                AuthSchemeResolverGenerator.DEFAULT_NAME,
                SmithyGoTypes.Transport.Http.AuthScheme,
                schemeMappings);
    }

    @SuppressWarnings("checkstyle:LineLength")
    private GoWriter.Writable generateInvokeOperation() {
        return goTemplate("""
                func (c *Client) invokeOperation(ctx $context:T, opID string, params interface{}, optFns []func(*Options), stackFns ...func($stack:P, Options) error) (result interface{}, metadata $metadata:T, err error) {
                    ctx = $clearStackValues:T(ctx)
                    $newStack:W
                    options := c.options.Copy()
                    $resolvers:W

                    for _, fn := range optFns {
                        fn(&options)
                    }

                    $finalizers:W

                    for _, fn := range stackFns {
                        if err := fn(stack, options); err != nil {
                            return nil, metadata, err
                        }
                    }

                    for _, fn := range options.APIOptions {
                        if err := fn(stack); err != nil {
                            return nil, metadata, err
                        }
                    }

                    $newStackHandler:W
                    result, metadata, err = handler.Handle(ctx, params)
                    if err != nil {
                        err = &$operationError:T{
                            ServiceID: ServiceID,
                            OperationName: opID,
                            Err: err,
                        }
                    }
                    return result, metadata, err
                }
                """,
                MapUtils.of(
                        "context", GoStdlibTypes.Context.Context,
                        "stack", SmithyGoTypes.Middleware.Stack,
                        "metadata", SmithyGoTypes.Middleware.Metadata,
                        "clearStackValues", SmithyGoTypes.Middleware.ClearStackValues,
                        "newStack", generateNewStack(),
                        "newStackHandler", generateNewStackHandler(),
                        "operationError", SmithyGoTypes.Smithy.OperationError,
                        "resolvers", GoWriter.ChainWritable.of(
                                getConfigResolvers(
                                        ConfigFieldResolver.Location.OPERATION,
                                        ConfigFieldResolver.Target.INITIALIZATION
                                ).map(this::generateConfigFieldResolver).toList()
                        ).compose(),
                        "finalizers", GoWriter.ChainWritable.of(
                                getConfigResolvers(
                                        ConfigFieldResolver.Location.OPERATION,
                                        ConfigFieldResolver.Target.FINALIZATION
                                ).map(this::generateConfigFieldResolver).toList()
                        ).compose()
                ));
    }

    private GoWriter.Writable generateNewStack() {
        ensureSupportedProtocol();
        return goTemplate("stack := $T(opID, $T)",
                SmithyGoTypes.Middleware.NewStack, SmithyGoTypes.Transport.Http.NewStackRequest);
    }

    private GoWriter.Writable generateNewStackHandler() {
        ensureSupportedProtocol();
        return goTemplate("handler := $T($T(options.HTTPClient), stack)",
                SmithyGoTypes.Middleware.DecorateHandler, SmithyGoTypes.Transport.Http.NewClientHandler);
    }

    private void ensureSupportedProtocol() {
        if (!applicationProtocol.isHttpProtocol()) {
            throw new UnsupportedOperationException(
                    "Protocols other than HTTP are not yet implemented: " + applicationProtocol);
        }
    }

    private Stream<ConfigFieldResolver> getConfigResolvers(
            ConfigFieldResolver.Location location, ConfigFieldResolver.Target target
    ) {
        return runtimePlugins.stream()
                .filter(it -> it.matchesService(model, service))
                .flatMap(it -> it.getConfigFieldResolvers().stream())
                .filter(it -> it.getLocation() == location && it.getTarget() == target);
    }

    private GoWriter.Writable generateInputContextFuncs() {
        return goTemplate("""
                type operationInputKey struct{}

                func setOperationInput(ctx $1T, input interface{}) $1T {
                    return $2T(ctx, operationInputKey{}, input)
                }

                func getOperationInput(ctx $1T) interface{} {
                    return $3T(ctx, operationInputKey{})
                }

                $4W
                """,
                GoStdlibTypes.Context.Context,
                SmithyGoTypes.Middleware.WithStackValue,
                SmithyGoTypes.Middleware.GetStackValue,
                new SetOperationInputContextMiddleware().generate());
    }

    private GoWriter.Writable generateAddProtocolFinalizerMiddleware() {
        ensureSupportedProtocol();
        return goTemplate("""
                func addProtocolFinalizerMiddlewares(stack $P, options $L, operation string) error {
                    $W
                    return nil
                }
                """,
                SmithyGoTypes.Middleware.Stack,
                CONFIG_NAME,
                GoWriter.ChainWritable.of(
                        ResolveAuthSchemeMiddlewareGenerator.generateAddToProtocolFinalizers(),
                        GetIdentityMiddlewareGenerator.generateAddToProtocolFinalizers(),
                        EndpointMiddlewareGenerator.generateAddToProtocolFinalizers(),
                        SignRequestMiddlewareGenerator.generateAddToProtocolFinalizers()
                ).compose(false));
    }
}
