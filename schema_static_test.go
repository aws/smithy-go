package smithy

import (
	"fmt"
	"testing"
	"unsafe"
)

type testIndexed struct{ V string }

func (*testIndexed) TraitID() ShapeID { return ShapeID{Namespace: "test", Name: "indexed"} }
func (*testIndexed) TraitIndex() int  { return 0 }

type testOther struct{}

func (*testOther) TraitID() ShapeID { return ShapeID{Namespace: "test", Name: "other"} }

type testShape struct {
	name    string
	typ     ShapeType
	members []testMember
	traits  uint32
}

type testMember struct {
	name   string
	target int // index into shapes
	traits uint32
}

// buildTestSet lays shapes out the way generated code does: each shape
// followed by its members.
func buildTestSet(shapes []testShape, traitSets []TraitSet) (*SchemaSet, []Schema) {
	set := &SchemaSet{Namespaces: []string{"test"}, Traits: traitSets}

	ords := make([]int, len(shapes))
	n := 0
	for i, sh := range shapes {
		ords[i] = n
		n += 1 + len(sh.members)
	}

	add := func(str string, target, traits int, count int, typ ShapeType, flags uint8) {
		off := len(set.Blob)
		set.Blob += str
		set.Records = append(set.Records, StaticRecord{
			StrOff: uint32(off), Target: uint32(target), Traits: uint32(traits),
			StrLen: uint16(len(str)), Count: uint16(count), Type: uint8(typ), Flags: flags,
		})
	}
	for i, sh := range shapes {
		add(sh.name, ords[i], int(sh.traits), len(sh.members), sh.typ, 0)
		for _, m := range sh.members {
			tgt := shapes[m.target]
			add(m.name, ords[m.target], int(m.traits), len(tgt.members), tgt.typ, StaticFlagMember)
		}
	}

	// name lookup tables the way the code generator emits them: one per shape
	// with more members than the linear scan limit
	for i, sh := range shapes {
		if len(sh.members) <= staticLinearMemberLimit {
			continue
		}
		var names []string
		for _, m := range sh.members {
			names = append(names, m.name)
		}
		seed, size, slots := buildTestHash(names)
		set.Hashes = append(set.Hashes, StaticHash{Tab: uint32(len(set.Slots)), Mask: uint32(size - 1), Seed: seed})
		set.Slots = append(set.Slots, slots...)
		id := uint16(len(set.Hashes))
		set.Records[ords[i]].Hash = id
		for k := range sh.members {
			set.Records[ords[i]+1+k].Hash = 0 // member records of this shape are not shapes
		}
		for row := range set.Records {
			if set.Records[row].Flags&StaticFlagMember != 0 && int(set.Records[row].Target) == ords[i] {
				set.Records[row].Hash = id
			}
		}
	}

	// aux the way the code generator computes it: only a list/set with its
	// "member", or a map with "key" then "value", gets a shortcut.
	auxOf := func(row int) uint32 {
		target := int(set.Records[row].Target)
		first := set.str(&set.Records[target+1])
		switch ShapeType(set.Records[row].Type) {
		case ShapeTypeList, ShapeTypeSet:
			if set.Records[target].Count == 1 && first == "member" {
				return staticKindList<<staticAuxShift | uint32(target)
			}
		case ShapeTypeMap:
			if set.Records[target].Count == 2 && first == "key" && set.str(&set.Records[target+2]) == "value" {
				return staticKindMap<<staticAuxShift | uint32(target)
			}
		}
		return 0
	}

	handles := make([]Schema, len(set.Records))
	for i := range handles {
		handles[i] = NewStaticSchema(set, uint32(i), auxOf(i))
	}
	return set, handles
}

func testFrozen() (handles []Schema, str, list, strct int) {
	idx := &testIndexed{V: "on-string"}
	other := &testOther{}
	traitSets := []TraitSet{
		{},
		{Indexed: []Trait{idx}, Other: []Trait{other}, DirectMask: 1}, // string shape
		{Indexed: []Trait{idx}, Other: []Trait{other}},                // member inheriting from string
	}

	shapes := []testShape{
		{name: "String", typ: ShapeTypeString, traits: 1},
		{name: "List", typ: ShapeTypeList, members: []testMember{{"member", 0, 2}}},
		{name: "Struct", typ: ShapeTypeStructure, members: []testMember{{"a", 0, 2}, {"b", 1, 0}}},
	}
	_, handles = buildTestSet(shapes, traitSets)
	// ordinals: String=0, List=1 (member=2), Struct=3 (a=4, b=5)
	return handles, 0, 1, 3
}

func TestFrozenSchema(t *testing.T) {
	h, str, list, strct := testFrozen()
	S, L, T := &h[str], &h[list], &h[strct]

	if got := T.ID(); got != (ShapeID{Namespace: "test", Name: "Struct"}) {
		t.Errorf("ID = %v", got)
	}
	if T.Type() != ShapeTypeStructure || L.Type() != ShapeTypeList {
		t.Errorf("types: %v %v", T.Type(), L.Type())
	}

	a, b := &h[strct+1], &h[strct+2]
	if T.Member("a") != a || T.Member("b") != b || T.Member("c") != nil {
		t.Error("Member lookup")
	}
	if m := T.Members(); len(m) != 2 || m["a"] != a || m["b"] != b {
		t.Errorf("Members = %v", m)
	}

	if a.MemberName() != "a" || T.MemberName() != "" {
		t.Error("MemberName")
	}
	if a.ID() != (ShapeID{Member: "a"}) || a.TargetID() != S.ID() || T.TargetID() != (ShapeID{}) {
		t.Errorf("member IDs: %v %v", a.ID(), a.TargetID())
	}
	if a.Type() != ShapeTypeString {
		t.Errorf("member type = %v", a.Type())
	}

	if L.ListMember() != &h[list+1] || b.ListMember() != &h[list+1] {
		t.Error("ListMember should resolve through the member's target")
	}
	if T.ListMember() != nil || T.MapKey() != nil || T.MapValue() != nil {
		t.Error("ListMember/MapKey/MapValue on a structure")
	}
}

func TestFrozenSchemaTraits(t *testing.T) {
	h, str, _, strct := testFrozen()
	S, a := &h[str], &h[strct+1]

	if v, ok := SchemaTrait[*testIndexed](S); !ok || v.V != "on-string" {
		t.Error("indexed trait on shape")
	}
	if _, ok := SchemaDirectTrait[*testIndexed](S); !ok {
		t.Error("shape's own trait is direct")
	}
	if _, ok := SchemaTrait[*testIndexed](a); !ok {
		t.Error("member inherits the target's trait")
	}
	if _, ok := SchemaDirectTrait[*testIndexed](a); ok {
		t.Error("inherited trait is not direct")
	}
	if _, ok := SchemaTrait[*testOther](a); !ok {
		t.Error("non-indexed trait")
	}
	if _, ok := SchemaTrait[*testOther](&h[strct]); ok {
		t.Error("trait absent from shape")
	}
}

func TestFrozenSchemaExtension(t *testing.T) {
	h, _, _, strct := testFrozen()
	T := &h[strct]

	calls := 0
	build := func(*Schema) *int { calls++; v := calls; return &v }
	first := SchemaExtension(T, ExtJSON, build)
	if SchemaExtension(T, ExtJSON, build) != first || calls != 1 {
		t.Errorf("extension not cached, calls = %d", calls)
	}
	if other := SchemaExtension(&h[strct+1], ExtJSON, build); other == first {
		t.Error("extensions must be per schema")
	}
	if SchemaExtension(T, ExtCBOR, build) == first {
		t.Error("extensions must be per slot")
	}
}

func TestFrozenSchemaManyMembers(t *testing.T) {
	var members []testMember
	for i := 0; i < 40; i++ {
		members = append(members, testMember{name: fmt.Sprintf("m%d", i), target: 0})
	}
	shapes := []testShape{
		{name: "String", typ: ShapeTypeString},
		{name: "Big", typ: ShapeTypeStructure, members: members},
		{name: "Holder", typ: ShapeTypeStructure, members: []testMember{{"ref", 1, 0}}},
	}
	_, h := buildTestSet(shapes, []TraitSet{{}})
	// rows: String=0, Big=1 (m0..m39 = 2..41), Holder=42 (ref=43)
	big, ref := &h[1], &h[43]

	if big.rec().Hash == 0 || ref.rec().Hash != big.rec().Hash {
		t.Fatalf("a lookup table is expected on the shape and on members that target it: %d %d", big.rec().Hash, ref.rec().Hash)
	}
	if h[2].rec().Hash != 0 {
		t.Errorf("a member of Big that targets String has no lookup table, got %d", h[2].rec().Hash)
	}

	for _, s := range []*Schema{big, ref} {
		for i := 0; i < 40; i++ {
			name := fmt.Sprintf("m%d", i)
			if s.Member(name) != &h[2+i] {
				t.Fatalf("lookup of %s", name)
			}
		}
		for _, miss := range []string{"m40", "m400", "M0", "", "m", "m00", "member", "m1x"} {
			if s.Member(miss) != nil {
				t.Errorf("Member(%q) found a member", miss)
			}
		}
		if len(s.Members()) != 40 {
			t.Errorf("Members has %d entries", len(s.Members()))
		}
	}
}

func TestFrozenSchemaAddMemberPanics(t *testing.T) {
	h, _, _, strct := testFrozen()
	defer func() {
		if recover() == nil {
			t.Error("expected panic")
		}
	}()
	h[strct].AddMember("x", &h[0])
}

func TestMutableMembersTracksAddMember(t *testing.T) {
	str := NewSchema(ShapeID{Namespace: "test", Name: "String"}, ShapeTypeString, 0)
	s := NewSchema(ShapeID{Namespace: "test", Name: "S"}, ShapeTypeStructure, 0)
	a := s.AddMember("a", str)
	if m := s.Members(); len(m) != 1 || m["a"] != a {
		t.Fatalf("Members = %v", m)
	}

	b := s.AddMember("b", str)
	if m := s.Members(); len(m) != 2 || m["b"] != b {
		t.Fatalf("Members after AddMember = %v", m)
	}
}

// Mutable schemas, as built by tests and hand-written code, must be able to
// refer to frozen generated schemas.
func TestMutableMemberOfFrozenTarget(t *testing.T) {
	h, str, list, strct := testFrozen()
	S, L, T := &h[str], &h[list], &h[strct]

	root := NewSchema(ShapeID{Namespace: "mine", Name: "Root"}, ShapeTypeStructure, 0)
	ms := root.AddMember("s", S, &testOther{})
	ml := root.AddMember("l", L)
	mt := root.AddMember("t", T)

	if ms.TargetID() != S.ID() || ms.Type() != ShapeTypeString || ms.MemberName() != "s" {
		t.Errorf("member of frozen string: %v %v %q", ms.TargetID(), ms.Type(), ms.MemberName())
	}
	if _, ok := SchemaTrait[*testIndexed](ms); !ok {
		t.Error("indexed trait should be inherited from the frozen target")
	}
	if _, ok := SchemaDirectTrait[*testIndexed](ms); ok {
		t.Error("inherited trait must not be direct")
	}
	if _, ok := SchemaDirectTrait[*testOther](ms); !ok {
		t.Error("trait declared on the member is present")
	}

	if got := ml.ListMember(); got != &h[list+1] {
		t.Errorf("ListMember of member targeting frozen list = %v", got)
	}

	if got := mt.Members(); len(got) != 2 || got["a"] != &h[strct+1] || got["b"] != &h[strct+2] {
		t.Errorf("members of member targeting frozen struct = %v", got)
	}
	if mt.Member("b") != &h[strct+2] {
		t.Error("lookup through member of frozen struct")
	}

	if got := root.Members(); len(got) != 3 || got["t"] != mt {
		t.Errorf("root members = %v", got)
	}
}

func TestFrozenMapAndListMembers(t *testing.T) {
	shapes := []testShape{
		{name: "String", typ: ShapeTypeString},
		{name: "KV", typ: ShapeTypeMap, members: []testMember{{"key", 0, 0}, {"value", 0, 0}}},
		{name: "VK", typ: ShapeTypeMap, members: []testMember{{"value", 0, 0}, {"key", 0, 0}}},
		{name: "L", typ: ShapeTypeList, members: []testMember{{"member", 0, 0}}},
		{name: "S", typ: ShapeTypeSet, members: []testMember{{"member", 0, 0}}},
	}
	_, h := buildTestSet(shapes, []TraitSet{{}})
	// rows: String=0, KV=1 (key=2, value=3), VK=4 (value=5, key=6), L=7 (member=8), S=9 (member=10)
	for _, c := range []struct {
		s          int
		key, value int
	}{{1, 2, 3}, {4, 6, 5}} {
		s := &h[c.s]
		if s.MapKey() != &h[c.key] || s.MapValue() != &h[c.value] {
			t.Errorf("map %d: key %p value %p", c.s, s.MapKey(), s.MapValue())
		}
		if s.MapKey().MemberName() != "key" || s.MapValue().MemberName() != "value" {
			t.Errorf("map %d: member names %q %q", c.s, s.MapKey().MemberName(), s.MapValue().MemberName())
		}
		if s.ListMember() != nil {
			t.Errorf("map %d: ListMember should be nil", c.s)
		}
	}
	for _, c := range []struct{ s, member int }{{7, 8}, {9, 10}} {
		s := &h[c.s]
		if s.ListMember() != &h[c.member] || s.MapKey() != nil || s.MapValue() != nil {
			t.Errorf("list %d: member %p key %p value %p", c.s, s.ListMember(), s.MapKey(), s.MapValue())
		}
	}

	// a member that targets a map or list resolves through its target
	_, h2 := buildTestSet([]testShape{
		{name: "String", typ: ShapeTypeString},
		{name: "KV", typ: ShapeTypeMap, members: []testMember{{"key", 0, 0}, {"value", 0, 0}}},
		{name: "Holder", typ: ShapeTypeStructure, members: []testMember{{"m", 1, 0}}},
	}, []TraitSet{{}})
	// rows: String=0, KV=1 (key=2, value=3), Holder=4 (m=5)
	m := &h2[5]
	if m.MapKey() != &h2[2] || m.MapValue() != &h2[3] {
		t.Errorf("member of map: key %p value %p", m.MapKey(), m.MapValue())
	}
}

func TestSchemaHandleSize(t *testing.T) {
	// aux is carried in what would otherwise be padding, so frozen handles stay
	// 24 bytes on 64-bit platforms.
	if unsafe.Sizeof(uintptr(0)) == 8 {
		if got := unsafe.Sizeof(Schema{}); got != 24 {
			t.Errorf("unsafe.Sizeof(Schema{}) = %d, want 24", got)
		}
	}
}

// buildTestHash searches for a collision-free seed, like the code generator.
func buildTestHash(names []string) (seed uint64, size int, slots []uint16) {
	size = 1
	for size < 2*len(names) {
		size <<= 1
	}
	for ; ; size <<= 1 {
		for seed = 1; seed < 1<<17; seed++ {
			slots = make([]uint16, size)
			ok := true
			for i, n := range names {
				idx := uint32(staticNameHash(n, seed)) & uint32(size-1)
				if slots[idx] != 0 {
					ok = false
					break
				}
				slots[idx] = uint16(i + 1)
			}
			if ok {
				return seed, size, slots
			}
		}
	}
}

// These values are shared with the code generator's test, which checks that its
// implementation of the hash produces the same results.
func TestStaticNameHashGolden(t *testing.T) {
	for _, c := range []struct {
		s    string
		seed uint64
		want uint64
	}{
		{"", 1, 0xa0761d6478bd642f},
		{"S", 1, 0xe5a866bb0f16611f},
		{"NULL", 7, 0x1d9396f36fff6906},
		{"abc", 3, 0x855c74105557a50c},
		{"BOOL", 0, 0x2698ed598a319299},
		{"12345678", 5, 0x66b834422ed040f4},
		{"DataSourceParameters", 12345, 0xc005316efcfc62e9},
		{"AmazonElasticsearchParameters", 99, 0x44587801b867f97f},
		{"0123456789abcdef", 42, 0x0f121383a229acfa},
		{"0123456789abcdefg", 42, 0x2ee0d19534d235d3},
		{"The quick brown fox jumps over the lazy dog, 40B", 0xDEADBEEFCAFEF00D, 0x286ba7241c5f194e},
	} {
		if got := staticNameHash(c.s, c.seed); got != c.want {
			t.Errorf("staticNameHash(%q, %#x) = %#016x, want %#016x", c.s, c.seed, got, c.want)
		}
	}
}

// A set without lookup tables, such as one written by hand, still resolves
// members by name.
func TestFrozenSchemaLargeShapeWithoutHash(t *testing.T) {
	var members []testMember
	for i := 0; i < 20; i++ {
		members = append(members, testMember{name: fmt.Sprintf("member%d", i), target: 0})
	}
	set, h := buildTestSet([]testShape{
		{name: "String", typ: ShapeTypeString},
		{name: "Big", typ: ShapeTypeStructure, members: members},
	}, []TraitSet{{}})
	if set.Records[1].Hash == 0 {
		t.Fatal("expected the builder to emit a lookup table")
	}

	for i := range set.Records {
		set.Records[i].Hash = 0
	}
	set.Hashes, set.Slots = nil, nil

	big := &h[1]
	for i := 0; i < 20; i++ {
		if got := big.Member(fmt.Sprintf("member%d", i)); got != &h[2+i] {
			t.Fatalf("member%d resolved to %p, want %p", i, got, &h[2+i])
		}
	}
	if big.Member("member20") != nil || big.Member("") != nil {
		t.Error("missing member found")
	}
}
