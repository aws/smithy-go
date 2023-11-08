/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.go.codegen;

import java.util.logging.Logger;
import software.amazon.smithy.build.PluginContext;
import software.amazon.smithy.build.SmithyBuildPlugin;
import software.amazon.smithy.codegen.core.SymbolProvider;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.utils.SmithyInternalApi;

/**
 * Plugin to trigger Go client code generation.
 */
@SmithyInternalApi
public class GoClientCodegenPlugin implements SmithyBuildPlugin {
    private static final Logger LOGGER = Logger.getLogger(GoClientCodegenPlugin.class.getName());

    @Override
    public String getName() {
        return "go-client-codegen";
    }

    @Override
    public void execute(PluginContext context) {
        String onlyBuild = System.getenv("SMITHY_GO_BUILD_API");
        if (onlyBuild != null && !onlyBuild.isEmpty()) {
            String targetServiceId = GoSettings.from(context.getSettings()).getService().toString();

            boolean found = false;
            for (String includeServiceId : onlyBuild.split(",")) {
                if (targetServiceId.startsWith(includeServiceId)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                LOGGER.info("skipping " + targetServiceId);
                return;
            }
        }

        new CodegenVisitor(context).execute();
    }

    /**
     * Creates a Go symbol provider.
     *
     * @param model    The model to generate symbols for.
     * @param settings The Gosettings to use to create symbol provider
     * @return Returns the created provider.
     */
    public static SymbolProvider createSymbolProvider(Model model, GoSettings settings) {
        return new SymbolVisitor(model, settings);
    }
}
