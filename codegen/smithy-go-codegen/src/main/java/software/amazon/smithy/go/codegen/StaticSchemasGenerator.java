package software.amazon.smithy.go.codegen;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.utils.SmithyInternalApi;
import software.amazon.smithy.utils.StringUtils;

/**
 * Generates the schemas of a service as package-level tables.
 *
 * <p>The output contains only constants and literals, so the Go compiler lays
 * it out statically in the binary instead of building it with code at startup.
 * Every schema (shape or member) is one fixed-size record in a table. Members
 * immediately follow their shape's record, in definition order. The exported
 * schema variables point at an array of small handles, one per record, which
 * keeps the pointer identity that generated serde code relies on.
 *
 * <p>The runtime side of this layout is {@code smithy.SchemaSet}.
 */
@SmithyInternalApi
public class StaticSchemasGenerator implements Writable {
    private static final String PRELUDE_NAMESPACE = "smithy.api";
    private static final int MAX_NAMESPACES = 127;
    private static final int MAX_STRING_LEN = 0xFFFF;
    private static final int MAX_MEMBERS = 0xFFFF;
    private static final int FLAG_MEMBER = 1;
    private static final int NAMESPACE_SHIFT = 1;
    private static final int BLOB_CHUNK = 100;

    // Layout of a handle's aux value, mirrored from schema_static.go in the Go runtime: the kind of the
    // exposed shape in the bits from AUX_SHIFT up, and the row of that shape below them.
    private static final int AUX_SHIFT = 30;

    // Shapes with more members than this get a lookup table; the runtime scans the others (staticLinearMemberLimit).
    private static final int LINEAR_MEMBER_LIMIT = 8;
    private static final int MAX_SEED = 1 << 17;
    private static final int MAX_SLOTS = 1 << 16;
    private static final int SLOTS_PER_LINE = 24;
    private static final int KIND_LIST = 1;
    private static final int KIND_MAP = 2;

    private final GoCodegenContext ctx;
    private final List<Shape> shapes;

    // all of the following are derived in accept()
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> entryByVar = new HashMap<>();
    private final Map<String, Entry> foreign = new LinkedHashMap<>();
    private final List<Record> records = new ArrayList<>();
    private final List<String> namespaces = new ArrayList<>();
    private final Map<String, Integer> traitSetIds = new HashMap<>();
    private final List<TraitSet> traitSets = new ArrayList<>();
    private final Map<String, Integer> stringOffsets = new HashMap<>();
    private final List<NameHash> nameHashes = new ArrayList<>();
    private final List<Integer> slots = new ArrayList<>();
    private String blob;

    /**
     * Creates the generator.
     *
     * @param ctx    the codegen context
     * @param shapes the shapes to generate schemas for, in output order. Prelude shapes are skipped.
     */
    public StaticSchemasGenerator(GoCodegenContext ctx, List<Shape> shapes) {
        this.ctx = ctx;
        this.shapes = shapes;
    }

    private static final class TraitRef {
        private final ShapeId id;
        private final int index; // -1 if the trait has no reserved index
        private final Trait trait;
        private final String key;

        private TraitRef(Trait trait) {
            this.trait = trait;
            this.id = trait.toShapeId();
            this.index = DefaultTraitGenerators.traitIndex(id);
            this.key = id + "=" + trait.toNode().toString();
        }
    }

    private static final class TraitSet {
        private final TreeMap<Integer, TraitRef> indexed = new TreeMap<>();
        private final TreeMap<String, TraitRef> other = new TreeMap<>();
        private long directMask;

        private String key() {
            var sb = new StringBuilder();
            indexed.forEach((i, t) -> sb.append(i).append(':').append(t.key).append(';'));
            sb.append('|');
            other.forEach((i, t) -> sb.append(t.key).append(';'));
            return sb.append('|').append(directMask).toString();
        }
    }

    // The name lookup table of one shape: a perfect hash over its member names.
    private static final class NameHash {
        private int tab;
        private int mask;
        private long seed;
    }

    private static final class Entry {
        private int hash; // 1 + index into nameHashes, or 0
        private Shape shape; // null for foreign entries
        private String varName;
        private String namespace;
        private String name;
        private int ord;
        private String type;
        private List<TraitRef> traits = List.of();
        private List<MemberShape> members = List.of();
    }

    private static final class Record {
        private String str;
        private int target;
        private int hash;
        private long aux;
        private int traits;
        private int count;
        private String type;
        private int flags;
    }

    @Override
    public void accept(GoWriter writer) {
        var service = ctx.service();
        var model = ctx.model();

        collectEntries(service);
        assignOrdinals();
        buildNameHashes();

        for (var entry : entries) {
            var shapeRecord = new Record();
            shapeRecord.str = entry.name;
            shapeRecord.target = entry.ord;
            shapeRecord.count = entry.members.size();
            shapeRecord.type = entry.type;
            shapeRecord.flags = namespaceIndex(entry.namespace) << NAMESPACE_SHIFT;
            shapeRecord.traits = internTraitSet(shapeTraits(entry));
            shapeRecord.aux = aux(entry, entry.ord);
            shapeRecord.hash = entry.hash;
            records.add(shapeRecord);

            for (var member : entry.members) {
                var target = model.expectShape(member.getTarget());
                var targetEntry = targetEntry(target, service);

                var r = new Record();
                r.str = member.getMemberName();
                r.target = targetEntry.ord;
                r.count = targetEntry.members.size();
                r.type = typeName(target);
                r.flags = FLAG_MEMBER;
                r.traits = internTraitSet(memberTraits(targetEntry, member));
                r.aux = aux(targetEntry, targetEntry.ord);
                r.hash = targetEntry.hash;
                records.add(r);
            }
        }

        for (var entry : foreign.values()) {
            var r = new Record();
            r.str = entry.name;
            r.target = entry.ord;
            r.type = entry.type;
            r.flags = namespaceIndex(entry.namespace) << NAMESPACE_SHIFT;
            r.traits = internTraitSet(new TraitSet());
            records.add(r);
        }

        packStrings();
        render(writer, service);
    }

    private void collectEntries(ServiceShape service) {
        var seen = new HashSet<String>();
        for (var shape : shapes) {
            if (Prelude.isPublicPreludeShape(shape)) {
                continue;
            }

            var varName = SchemaGenerator.getSchemaName(shape, service);
            if (!seen.add(varName)) {
                continue;
            }

            var modelId = SchemaGenerator.modelShapeID(shape);
            var entry = new Entry();
            entry.shape = shape;
            entry.varName = varName;
            entry.namespace = modelId.getNamespace();
            entry.name = modelId.getName();
            entry.type = typeName(shape);
            entry.members = new ArrayList<>(shape.members());
            if (shape.getType() == ShapeType.MAP) {
                // The runtime finds a map's key and value by position, so the key must come first.
                entry.members.sort(Comparator.comparingInt(m -> m.getMemberName().equals("key") ? 0 : 1));
            }
            entry.traits = traitRefs(shape.getAllTraits().values());
            if (entry.members.size() > MAX_MEMBERS) {
                throw new IllegalStateException("too many members in " + shape.getId());
            }

            entries.add(entry);
            entryByVar.put(varName, entry);
        }
    }

    private void assignOrdinals() {
        var ord = 0;
        for (var entry : entries) {
            entry.ord = ord;
            ord += 1 + entry.members.size();
        }

        // Prelude shapes targeted by members get a record of their own, after
        // everything else, in order of first reference.
        var service = ctx.service();
        for (var entry : entries) {
            for (var member : entry.members) {
                var target = ctx.model().expectShape(member.getTarget());
                if (!Prelude.isPublicPreludeShape(target)) {
                    continue;
                }

                var key = SchemaGenerator.getSchemaName(target, service);
                if (foreign.containsKey(key)) {
                    continue;
                }

                if (!target.members().isEmpty()) {
                    throw new IllegalStateException("prelude shape with members: " + target.getId());
                }

                var f = new Entry();
                f.varName = key;
                f.namespace = PRELUDE_NAMESPACE;
                f.name = target.getId().getName();
                f.type = typeName(target);
                f.ord = ord++;
                foreign.put(key, f);
            }
        }
    }

    private void buildNameHashes() {
        for (var entry : entries) {
            if (entry.members.size() <= LINEAR_MEMBER_LIMIT) {
                continue;
            }

            var names = entry.members.stream().map(MemberShape::getMemberName).toList();
            var table = findPerfectHash(names, entry.varName);
            table.hash.tab = slots.size();
            // slot value is the member's position + 1, so that 0 can mean empty
            slots.addAll(table.slotValues);
            nameHashes.add(table.hash);
            entry.hash = nameHashes.size();
        }
        if (nameHashes.size() > 0xFFFF) {
            throw new IllegalStateException("too many shapes with name lookup tables");
        }
    }

    private static final class PerfectHash {
        private final NameHash hash = new NameHash();
        private List<Integer> slotValues;
    }

    // Searches for a seed under which no two names share a slot, growing the table when none is found. The result
    // depends only on the names, so generation is deterministic.
    private static PerfectHash findPerfectHash(List<String> names, String shape) {
        int size = 1;
        while (size < 2 * names.size()) {
            size <<= 1;
        }

        for (; size <= MAX_SLOTS; size <<= 1) {
            for (long seed = 1; seed < MAX_SEED; seed++) {
                var table = new int[size];
                boolean ok = true;
                for (int i = 0; i < names.size(); i++) {
                    int slot = (int) (StaticNameHash.hash(names.get(i), seed) & (size - 1));
                    if (table[slot] != 0) {
                        ok = false;
                        break;
                    }
                    table[slot] = i + 1;
                }
                if (ok) {
                    var result = new PerfectHash();
                    result.hash.mask = size - 1;
                    result.hash.seed = seed;
                    result.slotValues = java.util.Arrays.stream(table).boxed().toList();
                    return result;
                }
            }
        }
        throw new IllegalStateException("no perfect hash found for " + shape + " with " + names.size() + " members");
    }

    // The shortcut for ListMember, MapKey and MapValue. Only shapes that have exactly the members the runtime
    // expects get one; anything else falls back to a lookup by name.
    private static long aux(Entry exposed, int row) {
        if (exposed.shape == null) {
            return 0;
        }
        if (row >= (1 << AUX_SHIFT)) {
            throw new IllegalStateException("too many schemas");
        }

        var names = exposed.members.stream().map(MemberShape::getMemberName).toList();
        var type = exposed.shape.getType();
        if ((type == ShapeType.LIST || type == ShapeType.SET) && names.equals(List.of("member"))) {
            return (long) KIND_LIST << AUX_SHIFT | row;
        }
        if (type == ShapeType.MAP && names.equals(List.of("key", "value"))) {
            return (long) KIND_MAP << AUX_SHIFT | row;
        }
        return 0;
    }

    private Entry targetEntry(Shape target, ServiceShape service) {
        var key = SchemaGenerator.getSchemaName(target, service);
        var entry = Prelude.isPublicPreludeShape(target) ? foreign.get(key) : entryByVar.get(key);
        if (entry == null) {
            throw new IllegalStateException("no schema generated for " + target.getId());
        }
        return entry;
    }

    private static String typeName(Shape shape) {
        return StringUtils.capitalize(shape.getType().toString());
    }

    private static List<TraitRef> traitRefs(java.util.Collection<Trait> traits) {
        var refs = new ArrayList<TraitRef>();
        for (var trait : traits) {
            if (DefaultTraitGenerators.forTrait(trait.toShapeId()) != null) {
                refs.add(new TraitRef(trait));
            }
        }
        return refs;
    }

    private static void put(TraitSet set, TraitRef ref, boolean direct) {
        if (ref.index >= 0) {
            set.indexed.put(ref.index, ref);
            if (direct) {
                set.directMask |= 1L << ref.index;
            }
        } else {
            set.other.put(ref.id.toString(), ref);
        }
    }

    private static TraitSet shapeTraits(Entry entry) {
        var set = new TraitSet();
        for (var ref : entry.traits) {
            put(set, ref, true);
        }
        return set;
    }

    // A member inherits all of its target's traits, then applies its own on
    // top of them. Only the member's own indexed traits are marked direct.
    private static TraitSet memberTraits(Entry target, MemberShape member) {
        var set = new TraitSet();
        for (var ref : target.traits) {
            put(set, ref, false);
        }
        for (var ref : traitRefs(member.getAllTraits().values())) {
            put(set, ref, true);
        }
        return set;
    }

    private int internTraitSet(TraitSet set) {
        var key = set.key();
        var id = traitSetIds.get(key);
        if (id != null) {
            return id;
        }

        id = traitSets.size();
        traitSets.add(set);
        traitSetIds.put(key, id);
        return id;
    }

    private int namespaceIndex(String namespace) {
        var i = namespaces.indexOf(namespace);
        if (i >= 0) {
            return i;
        }
        if (namespaces.size() >= MAX_NAMESPACES) {
            throw new IllegalStateException("too many namespaces");
        }
        namespaces.add(namespace);
        return namespaces.size() - 1;
    }

    // Strings that occur inside an already placed string share its bytes.
    private void packStrings() {
        var unique = new HashSet<String>();
        for (var r : records) {
            var bytes = r.str.getBytes(StandardCharsets.UTF_8);
            if (bytes.length != r.str.length()) {
                throw new IllegalStateException("non-ASCII name: " + r.str);
            }
            if (bytes.length > MAX_STRING_LEN) {
                throw new IllegalStateException("name too long: " + r.str);
            }
            unique.add(r.str);
        }

        var sorted = new ArrayList<>(unique);
        sorted.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));

        var sb = new StringBuilder();
        for (var s : sorted) {
            var off = sb.indexOf(s);
            if (off < 0) {
                off = sb.length();
                sb.append(s);
            }
            stringOffsets.put(s, off);
        }
        blob = sb.toString();
    }

    private void render(GoWriter writer, ServiceShape service) {
        writer.addUseImports(SmithyGoDependency.SMITHY);

        var sb = new StringBuilder();
        sb.append("const blob = \"\" +\n");
        for (int i = 0; i < blob.length(); i += BLOB_CHUNK) {
            sb.append('\t').append(quote(blob.substring(i, Math.min(blob.length(), i + BLOB_CHUNK))));
            sb.append(i + BLOB_CHUNK < blob.length() ? " +\n" : "\n");
        }
        if (blob.isEmpty()) {
            sb.append("\t\"\"\n");
        }
        writer.write("$L", sb.toString());

        writer.write("var set = smithy.SchemaSet{");
        writer.write("Blob: blob,");

        var ns = new StringBuilder("Namespaces: []string{");
        for (int i = 0; i < namespaces.size(); i++) {
            ns.append(i > 0 ? ", " : "").append(quote(namespaces.get(i)));
        }
        writer.write("$L},", ns.toString());

        writer.write("Records: []smithy.StaticRecord{");
        for (var r : records) {
            writer.write("{StrOff: $L, Target: $L, Traits: $L, StrLen: $L, Count: $L, "
                            + "Type: uint8(smithy.ShapeType$L), Flags: $L$L},",
                    stringOffsets.get(r.str), r.target, r.traits, r.str.length(), r.count, r.type, r.flags,
                    r.hash == 0 ? "" : ", Hash: " + r.hash);
        }
        writer.write("},");

        writer.write("Traits: []smithy.TraitSet{");
        for (var set : traitSets) {
            renderTraitSet(writer, set);
        }
        writer.write("},");

        if (!nameHashes.isEmpty()) {
            writer.write("Hashes: []smithy.StaticHash{");
            for (var h : nameHashes) {
                writer.write("{Tab: $L, Mask: $L, Seed: $L},", h.tab, h.mask, h.seed);
            }
            writer.write("},");

            writer.write("Slots: []uint16{");
            for (int i = 0; i < slots.size(); i += SLOTS_PER_LINE) {
                var line = new StringBuilder();
                for (int j = i; j < Math.min(slots.size(), i + SLOTS_PER_LINE); j++) {
                    line.append(slots.get(j)).append(", ");
                }
                writer.write("$L", line.toString().trim());
            }
            writer.write("},");
        }
        writer.write("}");
        writer.write("");

        writer.write("var handles = [$L]smithy.Schema{", records.size());
        for (int i = 0; i < records.size(); i++) {
            writer.write("smithy.NewStaticSchema(&set, $L, $L),", i, records.get(i).aux);
        }
        writer.write("}");
        writer.write("");

        for (var entry : entries) {
            writer.write("var $L = &handles[$L]", entry.varName, entry.ord);
            for (int i = 0; i < entry.members.size(); i++) {
                writer.write("var $L = &handles[$L]",
                        SchemaGenerator.getMemberSchemaName(entry.shape, entry.members.get(i), service),
                        entry.ord + 1 + i);
            }
            writer.write("");
        }

        var serviceEntry = entryByVar.get(SchemaGenerator.getSchemaName(service, service));
        writer.write("var $L = &smithy.ServiceSchema{Schema: &handles[$L], Version: $S}",
                StringUtils.capitalize(service.getId().getName(service)), serviceEntry.ord, service.getVersion());
    }

    private void renderTraitSet(GoWriter writer, TraitSet set) {
        writer.writeInline("{");
        if (!set.indexed.isEmpty()) {
            writer.writeInline("Indexed: []smithy.Trait{");
            var max = set.indexed.lastKey();
            for (int i = 0; i <= max; i++) {
                var ref = set.indexed.get(i);
                if (ref == null) {
                    writer.writeInline("nil, ");
                    continue;
                }
                renderTrait(writer, ref);
                writer.writeInline(", ");
            }
            writer.writeInline("}, ");
        }
        if (!set.other.isEmpty()) {
            writer.writeInline("Other: []smithy.Trait{");
            for (var ref : set.other.values()) {
                renderTrait(writer, ref);
                writer.writeInline(", ");
            }
            writer.writeInline("}, ");
        }
        if (set.directMask != 0) {
            writer.writeInline("DirectMask: $L, ", "0x" + Long.toHexString(set.directMask));
        }
        writer.write("},");
    }

    private void renderTrait(GoWriter writer, TraitRef ref) {
        var generator = DefaultTraitGenerators.forTrait(ref.id);
        writer.writeInline("$W", generator.render(ref.trait));
    }

    private static String quote(String s) {
        var sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            var c = s.charAt(i);
            if (c == '"' || c == '\\' || c < ' ' || c > '~') {
                throw new IllegalStateException("unexpected character in schema name: " + s);
            }
            sb.append(c);
        }
        return sb.append('"').toString();
    }
}
