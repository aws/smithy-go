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

import static software.amazon.smithy.go.codegen.GoWriter.emptyGoTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goDocTemplate;
import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.auth.AuthSchemeResolverGenerator;
import software.amazon.smithy.go.codegen.integration.AuthSchemeDefinition;
import software.amazon.smithy.go.codegen.integration.ClientMember;
import software.amazon.smithy.go.codegen.integration.ClientMemberResolver;
import software.amazon.smithy.go.codegen.integration.ConfigField;
import software.amazon.smithy.go.codegen.integration.ConfigFieldResolver;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.go.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.ServiceIndex;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.utils.MapUtils;

/**
 * Generates a service client and configuration.
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
        writer.write("$W", generate());
        writeProtocolResolverImpls();
    }

    private GoWriter.Writable generate() {
        return GoWriter.ChainWritable.of(
                generateMetadata(),
                generateClient(),
                generateNew(),
                generateOptions(),
                generateInvokeOperation()
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

                    $initializeResolvers:W

                    $protocolResolvers:W

                    for _, fn := range optFns {
                        fn(&options)
                    }

                    client := &$client:L{
                        options: options,
                    }

                    $finalizeResolvers:W

                    $clientMemberResolvers:W

                    return client
                }
                """, MapUtils.of(
                        "docs", docs,
                        "options", CONFIG_NAME,
                        "client", serviceSymbol.getName(),
                        "protocolResolvers", generateProtocolResolvers(),
                        "initializeResolvers", GoWriter.ChainWritable.of(
                                plugins.stream()
                                        .flatMap(it -> it.getConfigFieldResolvers().stream())
                                        .filter(it -> it.getLocation().equals(ConfigFieldResolver.Location.CLIENT))
                                        .filter(it -> it.getTarget().equals(ConfigFieldResolver.Target.INITIALIZATION))
                                        .map(this::generateConfigFieldResolver)
                                        .toList()
                        ).compose(),
                        "finalizeResolvers", GoWriter.ChainWritable.of(
                                plugins.stream()
                                        .flatMap(it -> it.getConfigFieldResolvers().stream())
                                        .filter(it -> it.getLocation().equals(ConfigFieldResolver.Location.CLIENT))
                                        .filter(it -> it.getTarget().equals(ConfigFieldResolver.Target.FINALIZATION))
                                        .map(this::generateConfigFieldResolver)
                                        .toList()
                        ).compose(),
                        "clientMemberResolvers", GoWriter.ChainWritable.of(
                                plugins.stream()
                                        .flatMap(it -> it.getClientMemberResolvers().stream())
                                        .map(this::generateClientMemberResolver)
                                        .toList()
                        ).compose()
                ));
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

    private GoWriter.Writable generateOptions() {
        var apiOptionsDocs = goDocTemplate(
                "Set of options to modify how an operation is invoked. These apply to all operations "
                        + "invoked for this client. Use functional options on operation call to modify this "
                        + "list for per operation behavior."
        );
        return goTemplate("""
                type $options:L struct {
                    $apiOptionsDocs:W
                    APIOptions []func($stack:P) error

                    $fields:W

                    $protocolFields:W
                }

                $getIdentityResolver:W

                $helpers:W

                $copy:W
                """, MapUtils.of(
                "apiOptionsDocs", apiOptionsDocs,
                "options", CONFIG_NAME,
                "stack", SmithyGoTypes.Middleware.Stack,
                "fields", GoWriter.ChainWritable.of(
                        getAllConfigFields().stream()
                                .map(this::writeConfigField)
                                .toList()
                ).compose(),
                "protocolFields", generateProtocolFields(),
                "getIdentityResolver", generateOptionsGetIdentityResolver(),
                "helpers", generateOptionsHelpers(),
                "copy", generateOptionsCopy()
        ));
    }

    private GoWriter.Writable writeConfigField(ConfigField field) {
        GoWriter.Writable docs = writer -> {
            field.getDocumentation().ifPresent(writer::writeDocs);
            field.getDeprecated().ifPresent(s -> {
                if (field.getDocumentation().isPresent()) {
                    writer.writeDocs("");
                }
                writer.writeDocs(String.format("Deprecated: %s", s));
            });
        };
        return goTemplate("""
                $W
                $L $P
                """, docs, field.getName(), field.getType());
    }

    private GoWriter.Writable generateOptionsHelpers() {
        return writer -> {
            writer.write("""
                    $W
                    func WithAPIOptions(optFns ...func($P) error) func(*Options) {
                        return func (o *Options) {
                            o.APIOptions = append(o.APIOptions, optFns...)
                        }
                    }
                    """,
                    goDocTemplate(
                            "WithAPIOptions returns a functional option for setting the Client's APIOptions option."
                    ),
                    SmithyGoTypes.Middleware.Stack);

            getAllConfigFields().stream().filter(ConfigField::getWithHelper).filter(ConfigField::isDeprecated)
                .forEach(configField -> {
                    writer.writeDocs(configField.getDeprecated().get());
                    writeWithHelperFunction(writer, configField);
                });

            getAllConfigFields().stream().filter(ConfigField::getWithHelper).filter(
                Predicate.not(ConfigField::isDeprecated))
                    .forEach(configField -> {
                        writer.writeDocs(
                                String.format(
                                    "With%s returns a functional option for setting the Client's %s option.",
                                        configField.getName(), configField.getName()));
                        writeWithHelperFunction(writer, configField);

                    });

            generateApplicationProtocolTypes(writer);
        };
    }

    private GoWriter.Writable generateOptionsCopy() {
        return goTemplate("""
                // Copy creates a clone where the APIOptions list is deep copied.
                func (o $1L) Copy() $1L {
                    to := o
                    to.APIOptions = make([]func($2P) error, len(o.APIOptions))
                    copy(to.APIOptions, o.APIOptions)

                    return to
                }
                """, CONFIG_NAME, SmithyGoTypes.Middleware.Stack);
    }

    private void writeWithHelperFunction(GoWriter writer, ConfigField configField) {
        writer.write("""
                func With$1L(v $2P) func(*Options) {
                    return func(o *Options) {
                        o.$1L = v
                    }
                }
                """, configField.getName(), configField.getType());
    }

    private List<ConfigField> getAllConfigFields() {
        List<ConfigField> configFields = new ArrayList<>();
        for (RuntimeClientPlugin runtimeClientPlugin : runtimePlugins) {
            if (!runtimeClientPlugin.matchesService(model, service)) {
                continue;
            }
            configFields.addAll(runtimeClientPlugin.getConfigFields());
        }
        return configFields.stream()
                .distinct()
                .sorted(Comparator.comparing(ConfigField::getName))
                .collect(Collectors.toList());
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

    private GoWriter.Writable generateProtocolFields() {
        ensureSupportedProtocol();
        return goTemplate("""
                $1W
                HTTPClient HTTPClient

                $2W
                AuthSchemeResolver $4L

                $3W
                AuthSchemes []$5T
                """,
                goDocTemplate("The HTTP client to invoke API calls with. "
                        + "Defaults to client's default HTTP implementation if nil."),
                goDocTemplate("The auth scheme resolver which determines how to authenticate for each operation."),
                goDocTemplate("The list of auth schemes supported by the client."),
                AuthSchemeResolverGenerator.INTERFACE_NAME,
                SmithyGoTypes.Transport.Http.AuthScheme);
    }

    private void generateApplicationProtocolTypes(GoWriter writer) {
        ensureSupportedProtocol();
        writer.write("""
                type HTTPClient interface {
                    Do($P) ($P, error)
                }
                """, GoStdlibTypes.Net.Http.Request, GoStdlibTypes.Net.Http.Response);
    }

    private GoWriter.Writable generateProtocolResolvers() {
        ensureSupportedProtocol();
        return goTemplate("""
                resolveAuthSchemeResolver(&options)

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
                    options.AuthSchemeResolver = &$L{}
                }

                func resolveAuthSchemes(options *Options) {
                    options.AuthSchemes = []$T{
                        $W
                    }
                }
                """,
                AuthSchemeResolverGenerator.DEFAULT_NAME,
                SmithyGoTypes.Transport.Http.AuthScheme,
                schemeMappings);
    }

    private GoWriter.Writable generateOptionsGetIdentityResolver() {
        return goTemplate("""
                func (o $L) GetIdentityResolver(schemeID string) $T {
                    $W
                    return nil
                }
                """,
                CONFIG_NAME,
                SmithyGoTypes.Auth.IdentityResolver,
                GoWriter.ChainWritable.of(
                        ServiceIndex.of(model)
                                .getEffectiveAuthSchemes(service).keySet().stream()
                                .filter(authSchemes::containsKey)
                                .map(trait -> generateGetIdentityResolverMapping(trait, authSchemes.get(trait)))
                                .toList()
                ).compose(false));
    }

    private GoWriter.Writable generateGetIdentityResolverMapping(ShapeId schemeId, AuthSchemeDefinition scheme) {
        return goTemplate("""
                if schemeID == $S {
                    return $W
                }""", schemeId.toString(), scheme.generateOptionsIdentityResolver());
    }

    @SuppressWarnings("checkstyle:LineLength")
    private GoWriter.Writable generateInvokeOperation() {
        var plugins = runtimePlugins.stream()
                .filter(it -> it.matchesService(model, service))
                .toList();
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
                                plugins.stream()
                                        .flatMap(it -> it.getConfigFieldResolvers().stream())
                                        .filter(it -> it.getLocation().equals(ConfigFieldResolver.Location.OPERATION))
                                        .filter(it -> it.getTarget().equals(ConfigFieldResolver.Target.INITIALIZATION))
                                        .map(this::generateConfigFieldResolver)
                                        .toList()
                        ).compose(),
                        "finalizers", GoWriter.ChainWritable.of(
                                plugins.stream()
                                        .flatMap(it -> it.getConfigFieldResolvers().stream())
                                        .filter(it -> it.getLocation().equals(ConfigFieldResolver.Location.OPERATION))
                                        .filter(it -> it.getTarget().equals(ConfigFieldResolver.Target.FINALIZATION))
                                        .map(this::generateConfigFieldResolver)
                                        .toList()
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
}
