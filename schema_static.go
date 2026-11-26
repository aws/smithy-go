package smithy

import (
	"math/bits"
	"sync/atomic"
)

// StaticSchema is the content of a [Schema].
//
// StaticSchema is exported for use by generated code only.
type StaticSchema struct {
	Member  string         // the member name, empty for shapes
	ID      *ShapeName     // the shape's name, or for a member, its target's
	Members *SchemaMembers // the shape's members, or for a member, its target's
	Traits  *TraitSet      // effective traits, nil if there are none
	Type    ShapeType
	Ext     *SchemaExtensions // required
}

// NewStaticSchema returns the schema described by d.
//
// Generated code calls this to declare its schemas as package-level variables.
// The function is small enough to be inlined, which lets the compiler
// initialize those variables statically. It is exported for use by generated
// code only.
func NewStaticSchema(d StaticSchema) Schema {
	return Schema{d: d}
}

// SchemaMembers holds the members of a shape. It is shared by the shape and by
// every member that targets the shape.
//
// SchemaMembers is exported for use by generated code only.
type SchemaMembers struct {
	// List holds the members in definition order, except that a map's key
	// always comes before its value.
	List []*Schema

	// Hash is an optional lookup table over the member names.
	Hash *MemberHash

	byName map[string]*Schema // schemas built with NewSchema only

	cache atomic.Pointer[map[string]*Schema] // Members() of static schemas
}

// ShapeName is the namespace and name of a shape.
//
// ShapeName is exported for use by generated code only.
type ShapeName struct {
	Namespace, Name string
}

// MemberHash is a perfect hash table over the member names of a shape: slot
// staticNameHash(name, Seed) & (len(Slots)-1) holds 1 + the member's position
// in [SchemaMembers.List], or 0. The length of Slots is a power of two.
//
// MemberHash is exported for use by generated code only.
type MemberHash struct {
	Slots []uint16
	Seed  uint64
}

// staticLinearMemberLimit is the member count at or below which generated
// code does not emit a hash table, so lookups scan the members.
const staticLinearMemberLimit = 8

func (ms *SchemaMembers) at(i int) *Schema {
	if ms == nil || i >= len(ms.List) {
		return nil
	}
	return ms.List[i]
}

func (ms *SchemaMembers) lookup(name string) *Schema {
	if h := ms.Hash; h != nil {
		mask := uint64(len(h.Slots) - 1)
		i := h.Slots[staticNameHash(name, h.Seed)&mask]
		if i == 0 || int(i) > len(ms.List) {
			return nil
		}
		if m := ms.List[i-1]; m.d.Member == name {
			return m
		}
		return nil
	}

	if ms.byName != nil {
		return ms.byName[name]
	}

	for _, m := range ms.List {
		if m.d.Member == name {
			return m
		}
	}
	return nil
}

//go:noinline
func (ms *SchemaMembers) buildMap() map[string]*Schema {
	m := make(map[string]*Schema, len(ms.List))
	for _, v := range ms.List {
		m[v.d.Member] = v
	}
	if ms.cache.CompareAndSwap(nil, &m) {
		return m
	}
	return *ms.cache.Load()
}

// add appends a member to a schema built with NewSchema. A map's key is kept
// before its value.
func (ms *SchemaMembers) add(typ ShapeType, m *Schema) {
	name := m.d.Member
	if old, ok := ms.byName[name]; ok {
		for i, v := range ms.List {
			if v == old {
				ms.List[i] = m
			}
		}
		ms.byName[name] = m
		return
	}

	ms.byName[name] = m
	ms.List = append(ms.List, m)
	if typ == ShapeTypeMap && name == "key" {
		copy(ms.List[1:], ms.List[:len(ms.List)-1])
		ms.List[0] = m
	}
}

// TraitSet is the effective set of traits of a schema. Generated code
// deduplicates them, so many schemas share one.
//
// TraitSet is exported for use by generated code only.
type TraitSet struct {
	Indexed    []Trait // by trait index, sized to the highest index present
	Other      []Trait // traits without an index
	DirectMask uint64  // bit i is set if Indexed[i] was declared directly on the schema
}

func (ts *TraitSet) add(t Trait, direct bool) {
	if it, ok := t.(IndexableTrait); ok {
		idx := it.TraitIndex()
		if idx >= len(ts.Indexed) {
			ts.Indexed = append(ts.Indexed, make([]Trait, idx-len(ts.Indexed)+1)...)
		}
		ts.Indexed[idx] = t
		if direct {
			ts.DirectMask |= 1 << uint(idx)
		}
		return
	}

	id := t.TraitID()
	for i, o := range ts.Other {
		if o.TraitID() == id {
			ts.Other[i] = t
			return
		}
	}
	ts.Other = append(ts.Other, t)
}

func (ts *TraitSet) other(id ShapeID) Trait {
	if ts == nil {
		return nil
	}
	for _, t := range ts.Other {
		if t.TraitID() == id {
			return t
		}
	}
	return nil
}

const (
	hashK0 = 0x9E3779B97F4A7C15
	hashK1 = 0xA0761D6478BD642F
)

// staticNameHash is the hash that the generated member lookup tables are built
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
