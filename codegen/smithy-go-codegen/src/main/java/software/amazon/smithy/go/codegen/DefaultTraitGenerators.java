package software.amazon.smithy.go.codegen;

import static software.amazon.smithy.go.codegen.SmithyGoDependency.SMITHY_TRAITS;

import java.util.HashMap;
import java.util.Map;
import software.amazon.smithy.aws.traits.protocols.AwsQueryErrorTrait;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.EventHeaderTrait;
import software.amazon.smithy.model.traits.EventPayloadTrait;
import software.amazon.smithy.model.traits.HostLabelTrait;
import software.amazon.smithy.model.traits.HttpErrorTrait;
import software.amazon.smithy.model.traits.HttpHeaderTrait;
import software.amazon.smithy.model.traits.HttpLabelTrait;
import software.amazon.smithy.model.traits.HttpPayloadTrait;
import software.amazon.smithy.model.traits.HttpPrefixHeadersTrait;
import software.amazon.smithy.model.traits.HttpQueryParamsTrait;
import software.amazon.smithy.model.traits.HttpQueryTrait;
import software.amazon.smithy.model.traits.HttpResponseCodeTrait;
import software.amazon.smithy.model.traits.JsonNameTrait;
import software.amazon.smithy.model.traits.MediaTypeTrait;
import software.amazon.smithy.model.traits.SensitiveTrait;
import software.amazon.smithy.model.traits.StreamingTrait;
import software.amazon.smithy.model.traits.TimestampFormatTrait;
import software.amazon.smithy.model.traits.XmlAttributeTrait;
import software.amazon.smithy.model.traits.XmlFlattenedTrait;
import software.amazon.smithy.model.traits.XmlNameTrait;
import software.amazon.smithy.model.traits.XmlNamespaceTrait;
import software.amazon.smithy.rulesengine.traits.ContextParamTrait;

public class DefaultTraitGenerators {
    private static final Map<ShapeId, TraitGenerator> GENERATORS = new HashMap<>();

    static {
        // Documentation traits
        GENERATORS.put(SensitiveTrait.ID, new SimpleTraitGenerator<SensitiveTrait>(SMITHY_TRAITS.struct("Sensitive")));

        // Serialization and Protocol traits
        GENERATORS.put(JsonNameTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("JSONName"),
                "Name", JsonNameTrait::getValue));
        GENERATORS.put(MediaTypeTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("MediaType"),
                "Type", MediaTypeTrait::getValue));
        GENERATORS.put(TimestampFormatTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("TimestampFormat"),
                "Format", TimestampFormatTrait::getValue));
        GENERATORS.put(XmlAttributeTrait.ID, new SimpleTraitGenerator<XmlAttributeTrait>(SMITHY_TRAITS.struct("XMLAttribute")));
        GENERATORS.put(XmlFlattenedTrait.ID, new SimpleTraitGenerator<XmlFlattenedTrait>(SMITHY_TRAITS.struct("XMLFlattened")));
        GENERATORS.put(XmlNameTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("XMLName"),
                "Name", XmlNameTrait::getValue));
        GENERATORS.put(XmlNamespaceTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("XMLNamespace"),
                "URI", XmlNamespaceTrait::getUri,
                "Prefix", XmlNamespaceTrait::getPrefix));

        // Streaming
        GENERATORS.put(EventHeaderTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("EventHeader")));
        GENERATORS.put(EventPayloadTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("EventPayload")));
        GENERATORS.put(StreamingTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("Streaming")));

        // HTTP bindings
        GENERATORS.put(HttpHeaderTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("HTTPHeader"),
                "Name", HttpHeaderTrait::getValue));
        GENERATORS.put(HttpLabelTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("HTTPLabel")));
        GENERATORS.put(HttpPayloadTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("HTTPPayload")));
        GENERATORS.put(HttpPrefixHeadersTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("HTTPPrefixHeaders"),
                "Prefix", HttpPrefixHeadersTrait::getValue));
        GENERATORS.put(HttpQueryTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("HTTPQuery"),
                "Name", HttpQueryTrait::getValue));
        GENERATORS.put(HttpQueryParamsTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("HTTPQueryParams")));
        GENERATORS.put(HttpResponseCodeTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("HTTPResponseCode")));

        // Endpoint Traits
        GENERATORS.put(HostLabelTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("HostLabel")));

        // Other traits
        GENERATORS.put(ContextParamTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("ContextParam")));
        GENERATORS.put(AwsQueryErrorTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("AWSQueryError"),
                "ErrorCode", AwsQueryErrorTrait::getCode,
                "StatusCode", AwsQueryErrorTrait::getHttpResponseCode));
    }

    public static TraitGenerator forTrait(ShapeId id) {
        return GENERATORS.get(id);
    }

    private DefaultTraitGenerators() {
    }
}
