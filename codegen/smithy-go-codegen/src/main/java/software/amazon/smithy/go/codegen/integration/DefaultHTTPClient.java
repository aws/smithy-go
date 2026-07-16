package software.amazon.smithy.go.codegen.integration;

import static software.amazon.smithy.go.codegen.GoWriter.goTemplate;
import static software.amazon.smithy.go.codegen.SymbolUtils.buildPackageSymbol;

import java.util.List;
import software.amazon.smithy.go.codegen.GoCodegenContext;
import software.amazon.smithy.go.codegen.SmithyGoDependency;

/**
 * Defaults the client's HTTPClient config field when the caller does not supply one.
 *
 * <p>This is the generic smithy-go default (a bare {@code *net/http.Client}). AWS SDK clients replace this
 * integration with one that provides their own HTTP client (with sane transport defaults) via {@link Replaces}.
 */
public class DefaultHTTPClient implements GoIntegration {
    private static final ConfigFieldResolver RESOLVE_HTTP_CLIENT = ConfigFieldResolver.builder()
            .resolver(buildPackageSymbol("resolveHTTPClient"))
            .location(ConfigFieldResolver.Location.CLIENT)
            .target(ConfigFieldResolver.Target.INITIALIZATION)
            .build();

    @Override
    public List<RuntimeClientPlugin> getClientPlugins() {
        return List.of(
                RuntimeClientPlugin.builder()
                        .addConfigFieldResolver(RESOLVE_HTTP_CLIENT)
                        .build()
        );
    }

    @Override
    public void writeAdditionalFiles(GoCodegenContext ctx) {
        ctx.writerDelegator().useFileWriter("api_client.go", ctx.settings().getModuleName(), goTemplate("""
                func resolveHTTPClient(o *Options) {
                    if o.HTTPClient == nil {
                        o.HTTPClient = &$T{}
                    }
                }
                """,
                SmithyGoDependency.NET_HTTP.struct("Client")));
    }
}
