package smithy

import (
	"sync/atomic"
	"unsafe"
)

// StaticFlagMember marks a [StaticRecord] as a member schema.
const StaticFlagMember = 1

// StaticNamespaceShift is the position in [StaticRecord.Flags] of the index of
// a shape's namespace in [SchemaSet.Namespaces].
const StaticNamespaceShift = 1

// staticLinearMemberLimit is the member count at or below which name lookups
// scan the member records instead of using a map.
const staticLinearMemberLimit = 8

// StaticRecord describes one frozen schema, either a shape or a member of a
// shape. It contains no pointers, so a table of records is not scanned by the
// garbage collector.
//
// Records are laid out so that the members of a shape immediately follow the
// shape's record, in definition order. Target is the ordinal of the shape
// record whose members this schema exposes: the schema's own ordinal for a
// shape, or the target shape's ordinal for a member.
//
// StaticRecord is exported for use by generated code only.
type StaticRecord struct {
	StrOff uint32 // offset into SchemaSet.Blob
	Target uint32
	Traits uint32 // index into SchemaSet.Traits
	StrLen uint16 // shape or member name
	Count  uint16 // number of members, same as the target shape's count
	Type   uint8
	Flags  uint8 // StaticFlagMember, namespace index << StaticNamespaceShift
}

// TraitSet is a deduplicated set of traits shared by frozen schemas.
//
// TraitSet is exported for use by generated code only.
type TraitSet struct {
	Indexed    []Trait // by trait index, as in a mutable schema
	Other      []Trait // traits without an index
	DirectMask uint64
}

// SchemaSet holds the frozen schemas of one package. Generated code declares
// it as a package-level variable composed only of constants and literals, so
// the compiler lays the whole thing out statically in the binary's data
// segment, with no initialization code and no heap allocation.
//
// SchemaSet is exported for use by generated code only.
type SchemaSet struct {
	Blob       string
	Namespaces []string
	Records    []StaticRecord
	Traits     []TraitSet

	tables [numTables]atomic.Pointer[slotTable]
}

// Slot tables are indexed by [ExtensionID] for codec extensions, followed by
// the tables used by the schema itself.
const (
	tableMap  = numExtensionSlots
	numTables = numExtensionSlots + 1
)

// slotTable is lazily allocated per-record mutable state, such as codec
// extensions.
type slotTable struct {
	slots []unsafe.Pointer
}

func (set *SchemaSet) table(i int) *slotTable {
	if t := set.tables[i].Load(); t != nil {
		return t
	}
	return set.newTable(i)
}

// newTable is the slow path of table, kept separate so that table can be inlined.
//
//go:noinline
func (set *SchemaSet) newTable(i int) *slotTable {
	t := &slotTable{slots: make([]unsafe.Pointer, len(set.Records))}
	if set.tables[i].CompareAndSwap(nil, t) {
		return t
	}
	return set.tables[i].Load()
}

func (set *SchemaSet) str(r *StaticRecord) string {
	return set.Blob[r.StrOff : r.StrOff+uint32(r.StrLen)]
}

func (set *SchemaSet) id(r *StaticRecord) ShapeID {
	str := set.str(r)
	if r.Flags&StaticFlagMember != 0 {
		return ShapeID{Member: str}
	}

	return ShapeID{Namespace: set.Namespaces[r.Flags>>StaticNamespaceShift], Name: str}
}

func (s *Schema) rec() *StaticRecord {
	return &s.set.Records[s.ord]
}

// at returns the frozen handle with the given ordinal. Handles of a set are
// laid out contiguously by ordinal.
func (s *Schema) at(ord uint32) *Schema {
	delta := (int64(ord) - int64(s.ord)) * int64(unsafe.Sizeof(Schema{}))
	return (*Schema)(unsafe.Add(unsafe.Pointer(s), delta))
}

func (s *Schema) staticID() ShapeID {
	return s.set.id(s.rec())
}

func (s *Schema) staticTargetID() ShapeID {
	r := s.rec()
	if r.Flags&StaticFlagMember == 0 {
		return ShapeID{}
	}
	return s.set.id(&s.set.Records[r.Target])
}

func (s *Schema) staticMemberName() string {
	r := s.rec()
	if r.Flags&StaticFlagMember == 0 {
		return ""
	}
	return s.set.str(r)
}

// staticMembersMap returns the members by name. The map is built on first use
// and cached per target shape, so all members that target a shape share it.
func (s *Schema) staticMembersMap() map[string]*Schema {
	r := s.rec()
	if r.Count == 0 {
		return nil
	}

	t := s.set.table(tableMap)
	if p := atomic.LoadPointer(&t.slots[r.Target]); p != nil {
		return *(*map[string]*Schema)(p)
	}

	first := s.at(r.Target + 1)
	byName := make(map[string]*Schema, r.Count)
	for i := 0; i < int(r.Count); i++ {
		m := (*Schema)(unsafe.Add(unsafe.Pointer(first), i*int(unsafe.Sizeof(Schema{}))))
		byName[m.staticMemberName()] = m
	}
	atomic.StorePointer(&t.slots[r.Target], unsafe.Pointer(&byName))
	return byName
}

// A frozen schema's aux field lets ListMember, MapKey and MapValue find their
// result without reading the schema's record. Bits staticAuxShift and above
// hold the kind of the shape the schema exposes, and the bits below hold the
// row of that shape. Generated code only sets a kind when the shape is a list
// or set whose single member is "member", or a map whose members are "key"
// followed by "value". Every other schema has aux 0.
const (
	staticAuxShift = 30
	staticAuxMask  = 1<<staticAuxShift - 1

	staticKindList = 1
	staticKindMap  = 2
)

// staticListMember returns the "member" of a list or set.
func (s *Schema) staticListMember() *Schema {
	if s.aux>>staticAuxShift == staticKindList {
		return s.at(s.aux&staticAuxMask + 1)
	}
	return s.staticMember("member")
}

// staticMapMember returns the "key" or "value" of a map.
func (s *Schema) staticMapMember(key bool) *Schema {
	if s.aux>>staticAuxShift == staticKindMap {
		row := s.aux & staticAuxMask
		if key {
			return s.at(row + 1)
		}
		return s.at(row + 2)
	}

	if key {
		return s.staticMember("key")
	}
	return s.staticMember("value")
}

func (s *Schema) staticMember(name string) *Schema {
	r := s.rec()
	if r.Count == 0 {
		return nil
	}

	if r.Count > staticLinearMemberLimit {
		return s.staticMembersMap()[name]
	}

	recs := s.set.Records[r.Target+1 : r.Target+1+uint32(r.Count)]
	for i := range recs {
		if s.set.str(&recs[i]) == name {
			return s.at(r.Target + 1 + uint32(i))
		}
	}
	return nil
}

// NewStaticSchema returns the frozen schema described by record ord of set.
// aux is the list/map shortcut that the code generator computes for the record.
//
// Generated code calls this to declare an array with one handle per record of
// its [SchemaSet]. The function is small enough to be inlined, which lets the
// compiler initialize that array statically. It is exported for use by
// generated code only.
func NewStaticSchema(set *SchemaSet, ord, aux uint32) Schema {
	return Schema{set: set, ord: ord, aux: aux}
}
