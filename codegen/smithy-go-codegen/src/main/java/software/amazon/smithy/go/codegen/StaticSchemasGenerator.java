package software.amazon.smithy.go.codegen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.utils.SmithyInternalApi;
import software.amazon.smithy.utils.StringUtils;

/**
 * Generates the schemas of a service as package-level variables.
 *
 * <p>Every schema is a variable initialized with {@code smithy.NewStaticSchema}, which the Go compiler lays out
 * statically in the binary instead of building it with code at startup. A shape's members are a
 * {@code smithy.SchemaMembers} variable, shared by the shape and by every member that targets it.
 *
 * <p>Go rejects package-level variables whose initializers refer to each other in a cycle, which recursive shapes
 * would otherwise produce. For every member that closes such a cycle, the initializer leaves out the reference to
 * the target's members and an {@code init} function sets it.
 */
@SmithyInternalApi
public class StaticSchemasGenerator implements Writable {
    private static final String PRELUDE_NAMESPACE = "smithy.api";

    // Shapes with more members than this get a lookup table; the runtime scans the others (staticLinearMemberLimit).
    private static final int LINEAR_MEMBER_LIMIT = 8;
    private static final int MAX_SEED = 1 << 17;
    private static final int MAX_SLOTS = 1 << 16;
    private static final int SLOTS_PER_LINE = 24;

    private final GoCodegenContext ctx;
    private final List<Shape> shapes;

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> entryByVar = new HashMap<>();
    private final Map<String, Entry> prelude = new LinkedHashMap<>();
    private final Map<String, Integer> traitSetIds = new HashMap<>();
    private final List<TraitSet> traitSets = new ArrayList<>();

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
        private final int index; // -1 if the trait has no reserved index
        private final Trait trait;
        private final String id;
        private final String key;

        private TraitRef(Trait trait) {
            this.trait = trait;
            this.id = trait.toShapeId().toString();
            this.index = DefaultTraitGenerators.traitIndex(trait.toShapeId());
            this.key = id + "=" + trait.toNode().toString();
        }
    }

    private static final class TraitSet {
        private final TreeMap<Integer, TraitRef> indexed = new TreeMap<>();
        private final TreeMap<String, TraitRef> other = new TreeMap<>();
        private long directMask;

        private boolean isEmpty() {
            return indexed.isEmpty() && other.isEmpty() && directMask == 0;
        }

        private String key() {
            var sb = new StringBuilder();
            indexed.forEach((i, t) -> sb.append(i).append(':').append(t.key).append(';'));
            sb.append('|');
            other.forEach((i, t) -> sb.append(t.key).append(';'));
            return sb.append('|').append(directMask).toString();
        }
    }

    private static final class Entry {
        private Shape shape; // null for prelude entries
        private String varName;
        private String namespace;
        private String name;
        private String type;
        private List<TraitRef> traits = List.of();
        private List<MemberShape> members = List.of();
        private int[] slots;
        private long seed;
        private final Set<Integer> backEdges = new HashSet<>(); // positions of members that close a cycle

        private boolean hasMembers() {
            return !members.isEmpty();
        }
    }

    @Override
    public void accept(GoWriter writer) {
        var service = ctx.service();
        collectEntries(service);
        findBackEdges();
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
            if (entry.members.size() > LINEAR_MEMBER_LIMIT) {
                buildNameHash(entry);
            }

            entries.add(entry);
            entryByVar.put(varName, entry);
        }

        for (var entry : entries) {
            for (var member : entry.members) {
                var target = ctx.model().expectShape(member.getTarget());
                if (!Prelude.isPublicPreludeShape(target)) {
                    continue;
                }

                var key = SchemaGenerator.getSchemaName(target, service);
                if (prelude.containsKey(key)) {
                    continue;
                }
                if (!target.members().isEmpty()) {
                    throw new IllegalStateException("prelude shape with members: " + target.getId());
                }

                var p = new Entry();
                p.varName = key;
                p.namespace = PRELUDE_NAMESPACE;
                p.name = target.getId().getName();
                p.type = typeName(target);
                prelude.put(key, p);
            }
        }
    }

    // Marks the members whose reference to their target's members closes a cycle among the members variables, found
    // with a depth-first search in output order so that generation is deterministic.
    private void findBackEdges() {
        var state = new HashMap<Entry, Integer>(); // 1 = on the stack, 2 = done
        for (var root : entries) {
            if (!root.hasMembers() || state.containsKey(root)) {
                continue;
            }

            var stack = new ArrayDeque<int[]>();
            var nodes = new ArrayDeque<Entry>();
            state.put(root, 1);
            nodes.push(root);
            stack.push(new int[]{0});
            while (!nodes.isEmpty()) {
                var node = nodes.peek();
                var pos = stack.peek();
                if (pos[0] == node.members.size()) {
                    state.put(node, 2);
                    nodes.pop();
                    stack.pop();
                    continue;
                }

                var i = pos[0]++;
                var target = targetEntry(node.members.get(i));
                if (!target.hasMembers()) {
                    continue;
                }

                var s = state.get(target);
                if (s == null) {
                    state.put(target, 1);
                    nodes.push(target);
                    stack.push(new int[]{0});
                } else if (s == 1) {
                    node.backEdges.add(i);
                }
            }
        }
    }

    private void buildNameHash(Entry entry) {
        var names = entry.members.stream().map(MemberShape::getMemberName).toList();
        int size = 1;
        while (size < 2 * names.size()) {
            size <<= 1;
        }

        // Searches for a seed under which no two names share a slot, growing the table when none is found.
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
                    // slot value is the member's position + 1, so that 0 can mean empty
                    table[slot] = i + 1;
                }
                if (ok) {
                    entry.slots = table;
                    entry.seed = seed;
                    return;
                }
            }
        }
        throw new IllegalStateException("no perfect hash found for " + entry.varName);
    }

    private Entry targetEntry(MemberShape member) {
        var target = ctx.model().expectShape(member.getTarget());
        var key = SchemaGenerator.getSchemaName(target, ctx.service());
        var entry = Prelude.isPublicPreludeShape(target) ? prelude.get(key) : entryByVar.get(key);
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
            set.other.put(ref.id, ref);
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

    // Returns the name of the variable holding the trait set, or null for an empty set.
    private String internTraitSet(TraitSet set) {
        if (set.isEmpty()) {
            return null;
        }

        var key = set.key();
        var id = traitSetIds.get(key);
        if (id == null) {
            id = traitSets.size();
            traitSets.add(set);
            traitSetIds.put(key, id);
        }
        return "t" + id;
    }

    // Variable names. Schema names start with an upper case letter or an underscore, so these prefixes cannot
    // collide with them or with each other.
    private static String valueVar(String schemaName) {
        return "s" + schemaName;
    }

    private static String membersVar(Entry entry) {
        return "m" + entry.varName;
    }

    private static String idVar(Entry entry) {
        return entry.shape == null ? "p" + entry.name : "i" + entry.varName;
    }

    private String memberVar(Entry entry, MemberShape member) {
        return SchemaGenerator.getMemberSchemaName(entry.shape, member, ctx.service());
    }

    private void render(GoWriter writer, ServiceShape service) {
        writer.addUseImports(SmithyGoDependency.SMITHY);

        var shapeLines = new ArrayList<String>();
        var memberLines = new ArrayList<String>();
        var patches = new ArrayList<String>();
        for (var entry : entries) {
            shapeLines.add(String.format("var %s = smithy.NewStaticSchema(%s)", valueVar(entry.varName),
                    staticSchema(null, idVar(entry), entry.hasMembers() ? membersVar(entry) : null,
                            internTraitSet(shapeTraits(entry)), entry.type)));

            for (int i = 0; i < entry.members.size(); i++) {
                var member = entry.members.get(i);
                var target = targetEntry(member);
                var traits = internTraitSet(memberTraits(target, member));
                var members = target.hasMembers() ? membersVar(target) : null;
                var name = valueVar(memberVar(entry, member));
                if (entry.backEdges.contains(i)) {
                    memberLines.add(String.format("var %s = smithy.NewStaticSchema(%s)", name,
                            staticSchema(member.getMemberName(), idVar(target), null, traits, target.type)));
                    patches.add(String.format("%s = smithy.NewStaticSchema(%s)", name,
                            staticSchema(member.getMemberName(), idVar(target), members, traits, target.type)));
                } else {
                    memberLines.add(String.format("var %s = smithy.NewStaticSchema(%s)", name,
                            staticSchema(member.getMemberName(), idVar(target), members, traits, target.type)));
                }
            }
        }

        writer.write("var (");
        for (var entry : entries) {
            writer.write("$L = smithy.ShapeID{Namespace: $S, Name: $S}", idVar(entry), entry.namespace, entry.name);
        }
        for (var entry : prelude.values()) {
            writer.write("$L = smithy.ShapeID{Namespace: $S, Name: $S}", idVar(entry), entry.namespace, entry.name);
        }
        writer.write(")");
        writer.write("");

        for (int i = 0; i < traitSets.size(); i++) {
            writer.writeInline("var t$L = smithy.TraitSet", i);
            renderTraitSet(writer, traitSets.get(i));
        }
        writer.write("");

        for (var entry : entries) {
            if (!entry.hasMembers()) {
                continue;
            }
            writer.writeInline("var $L = smithy.SchemaMembers{List: []*smithy.Schema{", membersVar(entry));
            for (var member : entry.members) {
                writer.writeInline("&$L, ", valueVar(memberVar(entry, member)));
            }
            writer.writeInline("}");
            if (entry.slots != null) {
                writer.write(", Seed: $L, Slots: []uint16{", entry.seed);
                for (int i = 0; i < entry.slots.length; i += SLOTS_PER_LINE) {
                    var line = Arrays.stream(entry.slots, i, Math.min(entry.slots.length, i + SLOTS_PER_LINE))
                            .mapToObj(Integer::toString)
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("");
                    writer.write("$L,", line);
                }
                writer.write("}}");
            } else {
                writer.write("}");
            }
        }
        writer.write("");

        for (var line : shapeLines) {
            writer.write("$L", line);
        }
        writer.write("");
        for (var line : memberLines) {
            writer.write("$L", line);
        }
        writer.write("");

        if (!patches.isEmpty()) {
            writer.writeDocs("Members of recursive shapes would make the variables' initializers refer to each other in "
                    + "a cycle, which Go rejects, so they are completed here.");
            writer.write("func init() {");
            for (var line : patches) {
                writer.write("$L", line);
            }
            writer.write("}");
            writer.write("");
        }

        for (var entry : entries) {
            writer.write("var $L = &$L", entry.varName, valueVar(entry.varName));
            for (var member : entry.members) {
                var name = memberVar(entry, member);
                writer.write("var $L = &$L", name, valueVar(name));
            }
            writer.write("");
        }

        writer.write("var $L = &smithy.ServiceSchema{Schema: &$L, Version: $S}",
                StringUtils.capitalize(service.getId().getName(service)),
                valueVar(SchemaGenerator.getSchemaName(service, service)), service.getVersion());
    }

    private static String staticSchema(String member, String id, String members, String traits, String type) {
        var sb = new StringBuilder("smithy.StaticSchema{");
        if (member != null) {
            sb.append("Member: ").append(quote(member)).append(", ");
        }
        sb.append("ID: &").append(id);
        if (members != null) {
            sb.append(", Members: &").append(members);
        }
        if (traits != null) {
            sb.append(", Traits: &").append(traits);
        }
        return sb.append(", Type: smithy.ShapeType").append(type).append("}").toString();
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
        writer.write("}");
    }

    private void renderTrait(GoWriter writer, TraitRef ref) {
        var generator = DefaultTraitGenerators.forTrait(ref.trait.toShapeId());
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
