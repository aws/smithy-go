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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import software.amazon.smithy.codegen.core.Symbol;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.go.codegen.integration.ConfigField;
import software.amazon.smithy.go.codegen.integration.GoIntegration;
import software.amazon.smithy.go.codegen.integration.RuntimeClientPlugin;
import software.amazon.smithy.go.codegen.integration.StackSlotRegistrar;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ServiceShape;

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
    }

    @Override
    public void run() {
        String serviceId = settings.getService().toString();
        for (GoIntegration integration : integrations) {
            serviceId = integration.processServiceId(settings, model, serviceId);
        }

        writer.write("const ServiceID = $S", serviceId);
        writer.write("const ServiceAPIVersion = $S", service.getVersion());
        writer.write("");

        Symbol serviceSymbol = symbolProvider.toSymbol(service);
        writer.writeDocs(String.format("%s provides the API client to make operations call for %s.",
                serviceSymbol.getName(),
                CodegenUtils.getServiceTitle(service, "the API")));
        writer.openBlock("type $T struct {", "}", serviceSymbol, () -> {
            writer.write("options $L", CONFIG_NAME);
        });

        generateConstructor(serviceSymbol);
        generateConfig();
        generateClientInvokeOperation();
    }

    private void generateConstructor(Symbol serviceSymbol) {
        writer.writeDocs(String.format("New returns an initialized %s based on the functional options. "
                + "Provide additional functional options to further configure the behavior "
                + "of the client, such as changing the client's endpoint or adding custom "
                + "middleware behavior.", serviceSymbol.getName()));
        Symbol optionsSymbol = SymbolUtils.createPointableSymbolBuilder(CONFIG_NAME).build();
        writer.openBlock("func New(options $T, optFns ...func($P)) $P {", "}", optionsSymbol, optionsSymbol,
                serviceSymbol, () -> {
                    writer.write("options = options.Copy()").write("");

                    // Run any config initialization functions registered by runtime plugins.
                    for (RuntimeClientPlugin runtimeClientPlugin : runtimePlugins) {
                        if (!runtimeClientPlugin.matchesService(model, service)
                                || !runtimeClientPlugin.getResolveFunction().isPresent()) {
                            continue;
                        }
                        writer.write("$T(&options)", runtimeClientPlugin.getResolveFunction().get());
                        writer.write("");
                    }

                    writer.openBlock("for _, fn := range optFns {", "}", () -> writer.write("fn(&options)"));
                    writer.write("");

                    writer.openBlock("client := &$T{", "}", serviceSymbol, () -> {
                        writer.write("options: options,");
                    }).write("");

                    writer.write("return client");
                });
    }

    private void generateConfig() {
        writer.openBlock("type $L struct {", "}", CONFIG_NAME, () -> {
            writer.writeDocs("Set of options to modify how an operation is invoked. These apply to all operations "
                    + "invoked for this client. Use functional options on operation call to modify this "
                    + "list for per operation behavior."
            );
            Symbol stackSymbol = SymbolUtils.createPointableSymbolBuilder("Stack", SmithyGoDependency.SMITHY_MIDDLEWARE)
                    .build();
            writer.write("APIOptions []func($P) error", stackSymbol).write("");

            // Add config fields to the options struct.
            for (ConfigField configField : getAllConfigFields()) {
                configField.getDocumentation().ifPresent(writer::writeDocs);
                writer.write("$L $P", configField.getName(), configField.getType());
                writer.write("");
            }

            generateApplicationProtocolConfig();
        }).write("");

        generateApplicationProtocolTypes();

        writer.writeDocs("Copy creates a clone where the APIOptions list is deep copied.");
        writer.openBlock("func (o $L) Copy() $L {", "}", CONFIG_NAME, CONFIG_NAME, () -> {
            writer.write("to := o");
            Symbol stackSymbol = SymbolUtils.createPointableSymbolBuilder("Stack", SmithyGoDependency.SMITHY_MIDDLEWARE)
                    .build();
            writer.write("to.APIOptions = make([]func($P) error, len(o.APIOptions))", stackSymbol);
            writer.write("copy(to.APIOptions, o.APIOptions)");
            writer.write("return to");
        });
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

    private void generateApplicationProtocolConfig() {
        ensureSupportedProtocol();
        writer.writeDocs(
                "The HTTP client to invoke API calls with. Defaults to client's default HTTP implementation if nil.");
        writer.write("HTTPClient HTTPClient").write("");
    }

    private void generateApplicationProtocolTypes() {
        ensureSupportedProtocol();
        writer.addUseImports(SmithyGoDependency.NET_HTTP);
        writer.openBlock("type HTTPClient interface {", "}", () -> {
            writer.write("Do(*http.Request) (*http.Response, error)");
        }).write("");
    }

    private void generateClientInvokeOperation() {
        writer.addUseImports(SmithyGoDependency.CONTEXT);
        writer.addUseImports(SmithyGoDependency.SMITHY);

        writer.openBlock("func (c *Client) invokeOperation("
                + "ctx context.Context, "
                + "opID string, "
                + "params interface{}, "
                + "optFns []func(*Options), "
                + "stackFns ...func(*middleware.Stack, Options) error"
                + ") "
                + "(result interface{}, metadata middleware.Metadata, err error) {", "}", () -> {
            writer.addUseImports(SmithyGoDependency.SMITHY_MIDDLEWARE);
            writer.addUseImports(SmithyGoDependency.SMITHY_HTTP_TRANSPORT);

            generateConstructStack();
            generateSlotInitialization();
            writer.write("options := c.options.Copy()");
            writer.write("for _, fn := range optFns { fn(&options) }");
            writer.write("");

            writer.openBlock("for _, fn := range stackFns {", "}", () -> {
                writer.write("if err := fn(stack, options); err != nil { return nil, metadata, err }");
            });
            writer.write("");

            writer.openBlock("for _, fn := range options.APIOptions {", "}", () -> {
                writer.write("if err := fn(stack); err != nil { return nil, metadata, err }");
            });
            writer.write("");

            generateConstructStackHandler();
            writer.write("result, metadata, err = handler.Handle(ctx, params)");
            writer.openBlock("if err != nil {", "}", () -> {
                writer.openBlock("err = &smithy.OperationError{", "}", () -> {
                    writer.write("ServiceID: ServiceID,");
                    writer.write("OperationName: opID,");
                    writer.write("Err: err,");
                });
            });
            writer.write("return result, metadata, err");
        });
    }

    private void generateSlotInitialization() {
        // Register stack slots provided by runtime plugins.
        StackSlotRegistrar slotRegistrar = resolveStackSlotRegistrar();
        slotRegistrar.generateSlotRegistration(writer, "stack");
    }

    private StackSlotRegistrar resolveStackSlotRegistrar() {
        StackSlotRegistrar.Builder builder = StackSlotRegistrar.builder();

        for (RuntimeClientPlugin runtimeClientPlugin : runtimePlugins) {
            if (!runtimeClientPlugin.matchesService(model, service)
                    || !runtimeClientPlugin.getResolveFunction().isPresent()) {
                Optional<StackSlotRegistrar> registrar = runtimeClientPlugin.getRegisterStackSlots();
                if (!registrar.isPresent()) {
                    continue;
                }
                builder = builder.merge(registrar.get());
            }
        }

        return builder.build();
    }

    private void generateConstructStack() {
        ensureSupportedProtocol();

        Symbol newStack = SymbolUtils.createValueSymbolBuilder(
                "NewStack", SmithyGoDependency.SMITHY_MIDDLEWARE).build();
        Symbol newStackRequest = SymbolUtils.createValueSymbolBuilder(
                "NewStackRequest", SmithyGoDependency.SMITHY_HTTP_TRANSPORT).build();

        writer.write("stack := $T(opID, $T)", newStack, newStackRequest);
    }

    private void generateConstructStackHandler() {
        ensureSupportedProtocol();

        Symbol decorateHandler = SymbolUtils.createValueSymbolBuilder(
                "DecorateHandler", SmithyGoDependency.SMITHY_MIDDLEWARE).build();
        Symbol newClientHandler = SymbolUtils.createValueSymbolBuilder(
                "NewClientHandler", SmithyGoDependency.SMITHY_HTTP_TRANSPORT).build();

        writer.write("handler := $T($T(options.HTTPClient), stack)", decorateHandler, newClientHandler);
    }

    private void ensureSupportedProtocol() {
        if (!applicationProtocol.isHttpProtocol()) {
            throw new UnsupportedOperationException(
                    "Protocols other than HTTP are not yet implemented: " + applicationProtocol);
        }
    }
}
