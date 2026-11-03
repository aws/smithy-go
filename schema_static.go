package smithy

import (
	"math/bits"
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
	Count  uint16 // number of members, same as the target shape's count (Hash is copied from the target too)
	Type   uint8
	Flags  uint8  // StaticFlagMember, namespace index << StaticNamespaceShift
	Hash   uint16 // 1 + index into SchemaSet.Hashes of the shape's name lookup table, or 0
}

// TraitSet is a deduplicated set of traits shared by frozen schemas.
//
// TraitSet is exported for use by generated code only.
type TraitSet struct {
	Indexed    []Trait // by trait index, as in a mutable schema
	Other      []Trait // traits without an index
	DirectMask uint64
}

// StaticHash locates the name lookup table of one shape that has many members.
// The table is a perfect hash: the generator picks Seed so that no two member
// names share a slot, so a lookup reads exactly one slot.
//
// StaticHash is exported for use by generated code only.
type StaticHash struct {
	Tab  uint32 // offset of the shape's first slot in SchemaSet.Slots
	Mask uint32 // number of slots minus one, always a power of two minus one
	Seed uint64
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
	Hashes     []StaticHash
	Slots      []uint16

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
	if r.Hash != 0 {
		return s.staticHashedMember(r, name)
	}

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

// staticHashedMember looks a member up through the shape's perfect hash table.
func (s *Schema) staticHashedMember(r *StaticRecord, name string) *Schema {
	h := &s.set.Hashes[r.Hash-1]
	slot := s.set.Slots[h.Tab+uint32(staticNameHash(name, h.Seed))&h.Mask]
	if slot == 0 {
		return nil
	}

	// member i of the shape is the record i+1 rows after it, and slot is i+1
	row := r.Target + uint32(slot)
	if s.set.str(&s.set.Records[row]) != name {
		return nil
	}
	return s.at(row)
}

const (
	hashK0 = 0x9E3779B97F4A7C15
	hashK1 = 0xA0761D6478BD642F
)

// staticNameHash is the hash that the generated name lookup tables are built
// with. The code generator implements the same function, and both are checked
// against the same golden values. Names are ASCII.
func staticNameHash(s string, seed uint64) uint64 {
	n := len(s)
	h := seed ^ uint64(n)*hashK0

	var a, b uint64
	switch {
	case n > 16:
		i := 0
		for ; n-i > 16; i += 16 {
			h = mix64(le64(s, i)^hashK1, le64(s, i+8)^h)
		}
		a, b = le64(s, n-16), le64(s, n-8)
	case n >= 8:
		a, b = le64(s, 0), le64(s, n-8)
	case n >= 4:
		a, b = uint64(le32(s, 0)), uint64(le32(s, n-4))
	case n > 0:
		a = uint64(s[0])<<16 | uint64(s[n>>1])<<8 | uint64(s[n-1])
	}
	return mix64(a^hashK1, b^h)
}

func mix64(a, b uint64) uint64 {
	hi, lo := bits.Mul64(a, b)
	return hi ^ lo
}

func le32(s string, i int) uint32 {
	return uint32(s[i]) | uint32(s[i+1])<<8 | uint32(s[i+2])<<16 | uint32(s[i+3])<<24
}

func le64(s string, i int) uint64 {
	return uint64(s[i]) | uint64(s[i+1])<<8 | uint64(s[i+2])<<16 | uint64(s[i+3])<<24 |
		uint64(s[i+4])<<32 | uint64(s[i+5])<<40 | uint64(s[i+6])<<48 | uint64(s[i+7])<<56
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
