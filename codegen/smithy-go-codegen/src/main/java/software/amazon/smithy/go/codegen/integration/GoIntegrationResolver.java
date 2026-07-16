package software.amazon.smithy.go.codegen.integration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import software.amazon.smithy.codegen.core.CodegenException;

/**
 * Resolves {@link Replaces} declarations across a set of loaded integrations.
 *
 * <p>Java's {@link java.util.ServiceLoader} has no mechanism to suppress a discovered service implementation, so
 * replacement is resolved here, after loading, by removing any integration that another integration declares it
 * replaces via {@link Replaces}.
 */
public final class GoIntegrationResolver {
    private static final Logger LOGGER = Logger.getLogger(GoIntegrationResolver.class.getName());

    private GoIntegrationResolver() {}

    /**
     * Returns the effective list of integrations with replaced integrations removed.
     *
     * <p>The replacement targets are computed in a single pass over all supplied integrations. An integration is
     * dropped if any supplied integration declares it as a replacement target, regardless of whether that replacer
     * is itself replaced (so mutually-replacing integrations both drop out). Targets are matched by exact class
     * identity. The {@link Replaces} annotation is repeatable, so one integration may declare multiple targets.
     *
     * @param integrations the loaded integrations, in their resolved order.
     * @return the integrations that survive replacement, preserving input order.
     * @throws CodegenException if an integration declares itself as a replacement target, or if two integrations
     *     declare a replacement of the same target.
     */
    public static List<GoIntegration> resolveReplacements(List<GoIntegration> integrations) {
        Map<Class<? extends GoIntegration>, Class<? extends GoIntegration>> replacedBy = new HashMap<>();
        for (GoIntegration integration : integrations) {
            for (Replaces replaces : integration.getClass().getAnnotationsByType(Replaces.class)) {
                var target = replaces.value();

                if (target.equals(integration.getClass())) {
                    throw new CodegenException("GoIntegration " + integration.getClass().getName()
                            + " declares itself as a replacement target.");
                }

                var existing = replacedBy.get(target);
                if (existing != null) {
                    throw new CodegenException(String.format(
                            "GoIntegrations %s and %s both declare a replacement of %s; a given integration may be "
                                    + "replaced by at most one other.",
                            existing.getName(), integration.getClass().getName(), target.getName()));
                }

                replacedBy.put(target, integration.getClass());
            }
        }

        if (replacedBy.isEmpty()) {
            return integrations;
        }

        return integrations.stream()
                .filter(integration -> {
                    var replacer = replacedBy.get(integration.getClass());
                    if (replacer != null) {
                        LOGGER.info(() -> String.format(
                                "Suppressing GoIntegration %s, replaced by %s.",
                                integration.getClass().getName(), replacer.getName()));
                        return false;
                    }
                    return true;
                })
                .toList();
    }
}
