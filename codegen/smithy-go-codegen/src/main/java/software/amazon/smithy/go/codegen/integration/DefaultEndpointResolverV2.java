package software.amazon.smithy.go.codegen.integration;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SymbolUtils.buildPackageSymbol;

import java.util.List;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.rulesengine.traits.EndpointBddTrait;
import software.amazon.smithy.rulesengine.traits.EndpointRuleSetTrait;

/**
 * Defaults EndpointResolverV2 for clients whose service models no endpoint rules, backing it with the client's
 * BaseEndpoint (the no-rules resolver emitted by EndpointResolverGenerator). Without this, such clients have a nil
 * resolver and error at request time.
 *
 * <p>AWS SDK clients initialize EndpointResolverV2 through their own endpoint resolution codegen, so they replace this
 * integration via {@link Replaces}.
 */
public class DefaultEndpointResolverV2 implements GoIntegration {
    private static boolean hasNoEndpointRules(Model model, ServiceShape service) {
        return !service.hasTrait(EndpointRuleSetTrait.class) && !service.hasTrait(EndpointBddTrait.class);
    }

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return List.of(
                RuntimeClientPlugin.builder()
                        .servicePredicate(DefaultEndpointResolverV2::hasNoEndpointRules)
                        .addConfigFieldResolver(ConfigFieldResolver.builder()
                                .resolver(buildPackageSymbol("resolveEndpointResolverV2"))
                                .location(ConfigFieldResolver.Location.CLIENT)
                                .target(ConfigFieldResolver.Target.FINALIZATION)
                                .build())
                        .build()
        );
    }

    @Override
    public void writeAdditionalFiles(GoCodegenContext ctx) {
        if (!hasNoEndpointRules(ctx.model(), ctx.settings().getService(ctx.model()))) {
            return;
        }

        ctx.writerDelegator().useFileWriter("endpoints.go", ctx.settings().getModuleName(), goTemplate("""
                // resolveEndpointResolverV2 defaults the client's EndpointResolverV2 to one backed by
                // BaseEndpoint when the caller has not supplied their own. Generated for services with no
                // modeled endpoint rules.
                func resolveEndpointResolverV2(options *Options) {
                    if options.EndpointResolverV2 == nil {
                        options.EndpointResolverV2 = &resolver{baseEndpoint: options.BaseEndpoint}
                    }
                }
                """));
    }
}
