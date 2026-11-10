package smithy

import (
	"fmt"
	"testing"
)

type testIndexed struct{ V string }

func (*testIndexed) TraitID() ShapeID { return ShapeID{Namespace: "test", Name: "indexed"} }
func (*testIndexed) TraitIndex() int  { return 0 }

type testOther struct{}

func (*testOther) TraitID() ShapeID { return ShapeID{Namespace: "test", Name: "other"} }

// The declarations below are laid out the way generated code declares them,
// including a recursive shape whose back edge is set in init.
var (
	tIdx   = &testIndexed{V: "on-string"}
	tOther = &testOther{}

	tsString       = TraitSet{Indexed: []Trait{tIdx}, Other: []Trait{tOther}, DirectMask: 1}
	tsStringMember = TraitSet{Indexed: []Trait{tIdx}, Other: []Trait{tOther}}

	idString = ShapeID{Namespace: "test", Name: "String"}
	idList   = ShapeID{Namespace: "test", Name: "List"}
	idMap    = ShapeID{Namespace: "test", Name: "Map"}
	idStruct = ShapeID{Namespace: "test", Name: "Struct"}
	idTree   = ShapeID{Namespace: "test", Name: "Tree"}

	tString = NewStaticSchema(StaticSchema{ID: &idString, Traits: &tsString, Type: ShapeTypeString})

	tList        = NewStaticSchema(StaticSchema{ID: &idList, Members: &tListMembers, Type: ShapeTypeList})
	tListMember  = NewStaticSchema(StaticSchema{Member: "member", ID: &idString, Traits: &tsStringMember, Type: ShapeTypeString})
	tListMembers = SchemaMembers{List: []*Schema{&tListMember}}

	tMap        = NewStaticSchema(StaticSchema{ID: &idMap, Members: &tMapMembers, Type: ShapeTypeMap})
	tMapKey     = NewStaticSchema(StaticSchema{Member: "key", ID: &idString, Traits: &tsStringMember, Type: ShapeTypeString})
	tMapValue   = NewStaticSchema(StaticSchema{Member: "value", ID: &idList, Members: &tListMembers, Type: ShapeTypeList})
	tMapMembers = SchemaMembers{List: []*Schema{&tMapKey, &tMapValue}}

	tStruct        = NewStaticSchema(StaticSchema{ID: &idStruct, Members: &tStructMembers, Type: ShapeTypeStructure})
	tStructA       = NewStaticSchema(StaticSchema{Member: "a", ID: &idString, Traits: &tsStringMember, Type: ShapeTypeString})
	tStructB       = NewStaticSchema(StaticSchema{Member: "b", ID: &idList, Members: &tListMembers, Type: ShapeTypeList})
	tStructMembers = SchemaMembers{List: []*Schema{&tStructA, &tStructB}}

	tTree        = NewStaticSchema(StaticSchema{ID: &idTree, Members: &tTreeMembers, Type: ShapeTypeStructure})
	tTreeChild   = NewStaticSchema(StaticSchema{Member: "child", ID: &idTree, Type: ShapeTypeStructure})
	tTreeMembers = SchemaMembers{List: []*Schema{&tTreeChild}}
)

func init() {
	tTreeChild = NewStaticSchema(StaticSchema{Member: "child", ID: &idTree, Members: &tTreeMembers, Type: ShapeTypeStructure})
}

func TestStaticSchema(t *testing.T) {
	S, L, T := &tString, &tList, &tStruct

	if got := T.ID(); got != idStruct {
		t.Errorf("ID = %v", got)
	}
	if T.Type() != ShapeTypeStructure || L.Type() != ShapeTypeList {
		t.Errorf("types: %v %v", T.Type(), L.Type())
	}

	a, b := &tStructA, &tStructB
	if T.Member("a") != a || T.Member("b") != b || T.Member("c") != nil || S.Member("a") != nil {
		t.Error("Member lookup")
	}
	if m := T.Members(); len(m) != 2 || m["a"] != a || m["b"] != b {
		t.Errorf("Members = %v", m)
	}
	if m1, m2 := T.Members(), T.Members(); fmt.Sprintf("%p", m1) != fmt.Sprintf("%p", m2) {
		t.Error("Members map is not cached")
	}
	if S.Members() != nil {
		t.Error("Members of a shape without members")
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

	if L.ListMember() != &tListMember || b.ListMember() != &tListMember {
		t.Error("ListMember should resolve through the member's target")
	}
	if tMap.MapKey() != &tMapKey || tMap.MapValue() != &tMapValue || tMapValue.ListMember() != &tListMember {
		t.Error("MapKey/MapValue")
	}
	if T.ListMember() != nil || T.MapKey() != nil || T.MapValue() != nil || L.MapKey() != nil || S.ListMember() != nil {
		t.Error("ListMember/MapKey/MapValue on the wrong type")
	}
}

func TestStaticSchemaRecursive(t *testing.T) {
	c := tTree.Member("child")
	if c != &tTreeChild || c.Member("child") != c || c.Member("child").Member("child") != c {
		t.Error("recursive member")
	}
	if c.TargetID() != idTree {
		t.Errorf("TargetID = %v", c.TargetID())
	}
}

func TestStaticSchemaTraits(t *testing.T) {
	S, a := &tString, &tStructA

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
	if _, ok := SchemaTrait[*testOther](&tStruct); ok {
		t.Error("trait absent from shape")
	}
	if _, ok := SchemaTrait[*testIndexed](&tStruct); ok {
		t.Error("indexed trait absent from shape")
	}
}

func TestStaticSchemaExtension(t *testing.T) {
	T := &tStruct

	calls := 0
	build := func(*Schema) *int { calls++; v := calls; return &v }
	first := SchemaExtension(T, ExtCBOR, build)
	if SchemaExtension(T, ExtCBOR, build) != first || calls != 1 {
		t.Errorf("extension not cached, calls = %d", calls)
	}
	if other := SchemaExtension(&tStructA, ExtCBOR, build); other == first {
		t.Error("extensions of different schemas are shared")
	}
}

func TestStaticSchemaAddMemberPanics(t *testing.T) {
	defer func() {
		if recover() == nil {
			t.Error("AddMember on a static schema did not panic")
		}
	}()
	tStruct.AddMember("c", &tString)
}

func TestHeapMemberOfStaticTarget(t *testing.T) {
	s := NewSchema(ShapeID{Namespace: "test", Name: "Outer"}, ShapeTypeStructure, 1)
	m := s.AddMember("inner", &tStruct, &testIndexed{V: "override"})

	if m.ID() != (ShapeID{Member: "inner"}) || m.TargetID() != idStruct || m.Type() != ShapeTypeStructure {
		t.Errorf("member: %v %v %v", m.ID(), m.TargetID(), m.Type())
	}
	if m.Member("a") != &tStructA || len(m.Members()) != 2 {
		t.Error("members of a static target")
	}
	if v, ok := SchemaDirectTrait[*testIndexed](m); !ok || v.V != "override" {
		t.Error("member override")
	}

	sm := s.AddMember("str", &tString)
	if v, ok := SchemaTrait[*testIndexed](sm); !ok || v.V != "on-string" {
		t.Error("inherited trait from a static target")
	}
	if _, ok := SchemaDirectTrait[*testIndexed](sm); ok {
		t.Error("inherited trait is direct")
	}
	if _, ok := SchemaTrait[*testOther](sm); !ok {
		t.Error("inherited non-indexed trait")
	}
	if v, _ := SchemaTrait[*testIndexed](&tString); v.V != "on-string" {
		t.Error("the static target's traits changed")
	}
}

func TestHeapSchemaMembersTrackAddMember(t *testing.T) {
	str := NewSchema(ShapeID{Namespace: "test", Name: "String"}, ShapeTypeString, 0)
	s := NewSchema(ShapeID{Namespace: "test", Name: "S"}, ShapeTypeStructure, 0)
	early := NewSchema(ShapeID{Namespace: "test", Name: "Holder"}, ShapeTypeStructure, 1).AddMember("s", s)

	a := s.AddMember("a", str)
	if early.Member("a") != a || len(early.Members()) != 1 {
		t.Error("a member added after the referencing member is not visible")
	}

	a2 := s.AddMember("a", str, &testOther{})
	if s.Member("a") != a2 || len(s.Members()) != 1 || s.d.Members.List[0] != a2 {
		t.Error("re-adding a member does not replace it")
	}
}

func TestHeapMapKeyBeforeValue(t *testing.T) {
	str := NewSchema(ShapeID{Namespace: "test", Name: "String"}, ShapeTypeString, 0)
	m := NewSchema(ShapeID{Namespace: "test", Name: "M"}, ShapeTypeMap, 2)
	v := m.AddMember("value", str)
	k := m.AddMember("key", str)
	if m.MapKey() != k || m.MapValue() != v {
		t.Error("map key and value order")
	}

	l := NewSchema(ShapeID{Namespace: "test", Name: "L"}, ShapeTypeList, 1)
	if l.ListMember() != nil {
		t.Error("ListMember of an empty list")
	}
	lm := l.AddMember("member", str)
	if l.ListMember() != lm {
		t.Error("ListMember")
	}
}

func TestStaticSchemaManyMembers(t *testing.T) {
	const n = 40
	var names []string
	list := make([]*Schema, n)
	for i := 0; i < n; i++ {
		names = append(names, fmt.Sprintf("Member%dName", i))
		s := NewStaticSchema(StaticSchema{Member: names[i], ID: &idString, Type: ShapeTypeString})
		list[i] = &s
	}

	seed, _, slots := buildTestHash(names)
	hashed := &SchemaMembers{List: list, Slots: slots, Seed: seed}
	linear := &SchemaMembers{List: list}
	for _, ms := range []*SchemaMembers{hashed, linear} {
		shape := NewStaticSchema(StaticSchema{ID: &idStruct, Members: ms, Type: ShapeTypeStructure})
		for i, name := range names {
			if got := shape.Member(name); got != list[i] {
				t.Fatalf("Member(%q) = %p, want %p", name, got, list[i])
			}
		}
		for _, miss := range []string{"", "Member", "Member0Nam", "Member0Namf", "nope", "Member40Name"} {
			if shape.Member(miss) != nil {
				t.Errorf("Member(%q) found a member", miss)
			}
		}
		if len(shape.Members()) != n {
			t.Error("Members")
		}
	}
}

func TestEventStreamDetection(t *testing.T) {
	stream := NewSchema(ShapeID{Namespace: "test", Name: "Stream"}, ShapeTypeUnion, 0, testStreaming{})
	out := NewSchema(ShapeID{Namespace: "test", Name: "Out"}, ShapeTypeStructure, 1)
	out.AddMember("events", stream)
	op := NewOperationSchema(nil, &tStruct, out)
	if op.IsInputEventStream() || !op.IsOutputEventStream() {
		t.Error("event stream detection")
	}
}

type testStreaming struct{}

func (testStreaming) TraitID() ShapeID { return ShapeID{Namespace: "smithy.api", Name: "streaming"} }
func (testStreaming) TraitIndex() int  { return indexStreaming }

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
				idx := staticNameHash(n, seed) & uint64(size-1)
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
