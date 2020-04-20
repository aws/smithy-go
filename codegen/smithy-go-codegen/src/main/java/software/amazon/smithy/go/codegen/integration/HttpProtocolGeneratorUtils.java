/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

import software.amazon.smithy.codegen.core.CodegenException;
import software.amazon.smithy.go.codegen.CodegenUtils;
import software.amazon.smithy.model.knowledge.HttpBinding;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.MediaTypeTrait;
import software.amazon.smithy.model.traits.TimestampFormatTrait;

import java.util.Optional;
import java.util.logging.Logger;

import static software.amazon.smithy.go.codegen.integration.ProtocolGenerator.*;

/**
 * Utility methods for generating HTTP protocols.
 */
public final class HttpProtocolGeneratorUtils {

    private static final Logger LOGGER = Logger.getLogger(HttpBindingProtocolGenerator.class.getName());

    private HttpProtocolGeneratorUtils() {}

    /**
     * Given a format and a source of data, generate an output value provider for the
     * timestamp.
     *
     * @param dataSource The in-code location of the data to provide an output of
     *                   ({@code output.foo}, {@code entry}, etc.)
     * @param bindingType How this value is bound to the operation output.
     * @param shape The shape that represents the value being received.
     * @param format The timestamp format to provide.
     * @return Returns a value or expression of the output timestamp.
     */
    public static String getTimestampOutputParam(String dataSource, HttpBinding.Location bindingType, Shape shape, TimestampFormatTrait.Format format) {
        switch (format) {
            case DATE_TIME:
            case HTTP_DATE:
            case EPOCH_SECONDS:
                break;
            default:
                throw new CodegenException("Unexpected timestamp format `" + format.toString() + "` on " + shape);
        }

        return "time.Time{}";
    }

    /**
     * Given a String output, determine its media type and generate an output value
     * provider for it.
     *
     * <p>This currently only supports using the LazyJsonString for {@code "application/json"}.
     *
     * @param context The generation context.
     * @param shape The shape that represents the value being received.
     * @param dataSource The in-code location of the data to provide an output of
     *   ({@code output.foo}, {@code entry}, etc.)
     * @return Returns a value or expression of the output string.
     */
    static String getStringOutputParam(GenerationContext context, Shape shape, String dataSource) {
        // Handle media type generation, defaulting to a standard String.
        Optional<MediaTypeTrait> mediaTypeTrait = shape.getTrait(MediaTypeTrait.class);
        if (mediaTypeTrait.isPresent()) {
            String mediaType = mediaTypeTrait.get().getValue();
            if (CodegenUtils.isJsonMediaType(mediaType)) {
                return dataSource;
            } else {
                LOGGER.warning(() -> "Found unsupported mediatype " + mediaType + " on String shape: " + shape);
            }
        }
        return dataSource;
    }
}
