package software.amazon.smithy.go.codegen.integration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that the annotated {@link GoIntegration} replaces another integration.
 *
 * <p>When an integration is replaced, it is removed in its entirety before code generation begins: none of its hooks
 * are invoked and none of its contributions (client plugins, auth scheme definitions, auth parameters, protocol
 * generators, etc.) are applied. This lets a downstream integration substitute its own behavior for a default
 * provided by an upstream one - for example, an AWS SDK integration replacing a generic smithy-go default.
 *
 * <p>The annotation is repeatable, so one integration may replace multiple targets:
 *
 * <pre>{@code
 * @Replaces(SigV4AuthScheme.class)
 * @Replaces(AnonymousAuthScheme.class)
 * public final class AwsSigV4AuthScheme implements GoIntegration { ... }
 * }</pre>
 *
 * <p>A given integration may be replaced by at most one other. If two integrations declare a replacement of the same
 * target, code generation fails rather than resolving the conflict silently.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(Replaces.Container.class)
public @interface Replaces {
    /**
     * The integration class that the annotated integration replaces.
     *
     * @return the replaced integration class.
     */
    Class<? extends GoIntegration> value();

    /**
     * Container for repeated {@link Replaces} declarations. Applied automatically by the compiler; not intended for
     * direct use.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Container {
        /**
         * The repeated declarations.
         *
         * @return the declarations.
         */
        Replaces[] value();
    }
}
