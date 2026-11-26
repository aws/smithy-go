package software.amazon.smithy.go.codegen;

import static software.amazon.smithy.go.codegen.SmithyGoDependency.SMITHY_TRAITS;

import java.util.HashMap;
import java.util.Map;
import software.amazon.smithy.aws.traits.protocols.AwsQueryCompatibleTrait;
import software.amazon.smithy.aws.traits.protocols.AwsQueryErrorTrait;
import software.amazon.smithy.aws.traits.protocols.Ec2QueryNameTrait;
import software.amazon.smithy.go.codegen.trait.BackfilledInputOutputTrait;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.traits.ErrorTrait;
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
import software.amazon.smithy.model.traits.HttpTrait;
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

    // Reserved slots of the traits that smithy.Schema indexes for fast lookup.
    // MUST match the constants in traits/index.go of the Go runtime. Traits
    // that are not listed here are looked up by shape ID.
    private static final Map<ShapeId, Integer> TRAIT_INDEXES = Map.ofEntries(
            Map.entry(JsonNameTrait.ID, 0),
            Map.entry(HttpTrait.ID, 1),
            Map.entry(HttpLabelTrait.ID, 2),
            Map.entry(XmlNameTrait.ID, 3),
            Map.entry(HttpQueryTrait.ID, 4),
            Map.entry(Ec2QueryNameTrait.ID, 5),
            Map.entry(HttpErrorTrait.ID, 6),
            Map.entry(HttpHeaderTrait.ID, 7),
            Map.entry(SensitiveTrait.ID, 8),
            Map.entry(AwsQueryErrorTrait.ID, 9),
            Map.entry(TimestampFormatTrait.ID, 10),
            Map.entry(HttpPayloadTrait.ID, 11),
            Map.entry(ContextParamTrait.ID, 12),
            Map.entry(HttpResponseCodeTrait.ID, 13),
            Map.entry(HostLabelTrait.ID, 14),
            Map.entry(XmlNamespaceTrait.ID, 15),
            Map.entry(XmlFlattenedTrait.ID, 16),
            Map.entry(StreamingTrait.ID, 17),
            Map.entry(MediaTypeTrait.ID, 18),
            Map.entry(HttpQueryParamsTrait.ID, 19),
            Map.entry(EventPayloadTrait.ID, 20),
            Map.entry(HttpPrefixHeadersTrait.ID, 21),
            Map.entry(EventHeaderTrait.ID, 22),
            Map.entry(XmlAttributeTrait.ID, 23),
            Map.entry(BackfilledInputOutputTrait.ID, 24)
    );

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
        GENERATORS.put(HttpTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("HTTP"),
                "Method", t -> ((HttpTrait) t).getMethod(),
                "URI", t -> ((HttpTrait) t).getUri().toString(),
                "Code", t -> ((HttpTrait) t).getCode()));
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
        GENERATORS.put(ErrorTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("Error"),
                "Type", ErrorTrait::getValue));
        GENERATORS.put(HttpErrorTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("HTTPError"),
                "Code", HttpErrorTrait::getCode));

        // Endpoint Traits
        GENERATORS.put(HostLabelTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("HostLabel")));

        // Other traits
        GENERATORS.put(ContextParamTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("ContextParam")));
        GENERATORS.put(AwsQueryErrorTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("AWSQueryError"),
                "ErrorCode", AwsQueryErrorTrait::getCode,
                "StatusCode", AwsQueryErrorTrait::getHttpResponseCode));
        GENERATORS.put(AwsQueryCompatibleTrait.ID, new SimpleTraitGenerator<AwsQueryCompatibleTrait>(SMITHY_TRAITS.struct("AWSQueryCompatible")));
        GENERATORS.put(Ec2QueryNameTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("EC2QueryName"),
                "Name", Ec2QueryNameTrait::getValue));

        // Codegen-internal traits
        GENERATORS.put(BackfilledInputOutputTrait.ID, new SimpleTraitGenerator<>(SMITHY_TRAITS.struct("UnitShape")));
    }

    public static TraitGenerator forTrait(ShapeId id) {
        return GENERATORS.get(id);
    }

    /**
     * Returns the reserved slot of the trait in a schema's indexed traits, or -1 if the trait has none.
     */
    public static int traitIndex(ShapeId id) {
        return TRAIT_INDEXES.getOrDefault(id, -1);
    }

    private DefaultTraitGenerators() {
    }
}
