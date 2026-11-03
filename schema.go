package smithy

import (
	"fmt"
	"maps"
	"strings"
	"sync/atomic"
	"unsafe"
)

// ShapeType is a type of Smithy shape.
// See https://smithy.io/2.0/spec/idl.html#defining-shapes.
type ShapeType int

// Enumerates ShapeType per the Smithy IDL.
const (
	ShapeTypeBlob ShapeType = iota
	ShapeTypeBoolean
	ShapeTypeString
	ShapeTypeTimestamp
	ShapeTypeByte
	ShapeTypeShort
	ShapeTypeInteger
	ShapeTypeLong
	ShapeTypeFloat
	ShapeTypeDocument
	ShapeTypeDouble
	ShapeTypeBigDecimal
	ShapeTypeBigInteger
	ShapeTypeEnum
	ShapeTypeIntEnum
	ShapeTypeList
	ShapeTypeSet
	ShapeTypeMap
	ShapeTypeStructure
	ShapeTypeUnion
	ShapeTypeMember
	ShapeTypeService
	ShapeTypeResource
	ShapeTypeOperation
)

// ShapeID fields of a Smithy shape ID.
type ShapeID struct {
	Namespace, Name, Member string
}

// String returns the IDL microformat for the shape ID.
func (s ShapeID) String() string {
	if s.Member == "" {
		return fmt.Sprintf("%s#%s", s.Namespace, s.Name)
	}
	return fmt.Sprintf("%s#%s$%s", s.Namespace, s.Name, s.Member)
}

func stoid(s string) ShapeID {
	ns, n, _ := strings.Cut(s, "#")
	n, m, _ := strings.Cut(n, "$")
	return ShapeID{ns, n, m}
}

// Schema encodes information about a shape from a Smithy model.
//
// Generated clients use schemas at runtime to dynamically (de)serialize
// request/responses.
//
// A Schema is in one of two forms. Schemas built with [NewSchema] and
// [Schema.AddMember] are mutable and heap allocated. Schemas that generated
// code lays out as package-level tables (see [SchemaSet]) are frozen handles
// into those tables, which the linker places in the binary's data segment
// instead of the heap.
type Schema struct {
	set *SchemaSet     // frozen schemas: the set this schema belongs to
	m   *mutableSchema // non-nil for mutable schemas

	// A frozen schema is its record. The record is one embedded value, copied
	// whole by [NewStaticSchema], so that the constructor stays cheap enough
	// for the compiler to inline it in packages with tens of thousands of
	// schemas, which is what lets it initialize them statically.
	staticRecord
}

type mutableSchema struct {
	id         ShapeID
	typ        ShapeType
	members    map[string]*Schema // member name -> schema
	traits     map[ShapeID]Trait  // trait ID -> non-indexed traits only
	indexed    []Trait            // indexed trait slots, sized to max index present
	directMask uint64             // bitmask: bit i set means indexed[i] was declared directly on this schema
	targetID   ShapeID            // for member schemas, the target's shape ID

	// resolved on the fly and cached
	listMember       atomic.Pointer[Schema]
	mapKey, mapValue atomic.Pointer[Schema]

	ext [numExtensionSlots]unsafe.Pointer // lazily-computed codec extensions, accessed atomically
}

// mutSchema lets a mutable schema and its state share one allocation.
type mutSchema struct {
	Schema
	mutableSchema
}

func newMutable() *Schema {
	ms := &mutSchema{}
	ms.Schema.m = &ms.mutableSchema
	return &ms.Schema
}

// NewSchema creates a new Schema with the given shape ID and traits.
func NewSchema(id ShapeID, typ ShapeType, numMembers int, ts ...Trait) *Schema {
	s := newMutable()
	s.m.id = id
	s.m.typ = typ
	s.m.members = make(map[string]*Schema, numMembers)
	for _, t := range ts {
		s.m.addTrait(t, true)
	}
	return s
}

func (m *mutableSchema) addTrait(t Trait, direct bool) {
	if it, ok := t.(IndexableTrait); ok {
		idx := it.TraitIndex()
		if idx >= len(m.indexed) {
			m.indexed = append(m.indexed, make([]Trait, idx-len(m.indexed)+1)...)
		}
		m.indexed[idx] = t
		if direct {
			m.directMask |= 1 << uint(idx)
		}
		return
	}

	if m.traits == nil {
		m.traits = map[ShapeID]Trait{}
	}
	m.traits[t.TraitID()] = t
}

// AddMember adds a member to the schema derived from the target, with
// optional trait overrides. The member schema is returned for caller
// reference.
//
// The member schema's effective trait view (accessed via [SchemaTrait])
// inherits all of the target's traits, then applies the overrides. The
// member's direct trait view (accessed via [SchemaDirectTrait]) contains
// only the overrides, i.e. the traits declared directly on the member.
//
// The target may be a mutable or a frozen schema, but AddMember panics if s
// itself is frozen.
func (s *Schema) AddMember(name string, target *Schema, ts ...Trait) *Schema {
	if s.m == nil {
		panic("smithy: AddMember on a frozen schema")
	}

	typ, members, indexed, other, targetID := target.inherit()

	m := newMutable()
	m.m.id = ShapeID{Member: name}
	m.m.typ = typ
	m.m.members = members
	m.m.indexed = indexed
	m.m.traits = other
	m.m.targetID = targetID // inherited traits are not direct

	// member-declared traits override and are direct
	for _, t := range ts {
		m.m.addTrait(t, true)
	}

	s.m.members[name] = m

	// Invalidate cached extensions, schema structure changed.
	for i := range s.m.ext {
		atomic.StorePointer(&s.m.ext[i], nil)
	}

	return m
}

// inherit returns the state a member schema derives from its target.
//
// A mutable target shares its members with the member, since members may
// still be added to the target. A frozen target cannot change, so the member
// gets a snapshot.
func (t *Schema) inherit() (typ ShapeType, members map[string]*Schema, indexed []Trait, other map[ShapeID]Trait, id ShapeID) {
	if m := t.m; m != nil {
		return m.typ, m.members, cloneIndexed(m.indexed), cloneTraits(m.traits), m.id
	}

	members = maps.Clone(t.staticMembersMap())

	ts := &t.set.Traits[t.Traits]
	if len(ts.Other) > 0 {
		other = make(map[ShapeID]Trait, len(ts.Other))
		for _, o := range ts.Other {
			other[o.TraitID()] = o
		}
	}
	return ShapeType(t.Shape), members, cloneIndexed(ts.Indexed), other, t.staticID()
}

func cloneIndexed(src []Trait) []Trait {
	if src == nil {
		return nil
	}
	dst := make([]Trait, len(src))
	copy(dst, src)
	return dst
}

func cloneTraits(src map[ShapeID]Trait) map[ShapeID]Trait {
	if src == nil {
		return nil
	}
	dst := make(map[ShapeID]Trait, len(src))
	for k, v := range src {
		dst[k] = v
	}
	return dst
}

// ListMember returns the "member" schema for list types.
func (s *Schema) ListMember() *Schema {
	if s.m == nil {
		return s.staticListMember()
	}
	return s.m.lookup(s, &s.m.listMember, "member")
}

// MapKey returns the "key" schema for map types.
func (s *Schema) MapKey() *Schema {
	if s.m == nil {
		return s.staticMapMember(true)
	}
	return s.m.lookup(s, &s.m.mapKey, "key")
}

// MapValue returns the "value" schema for map types.
func (s *Schema) MapValue() *Schema {
	if s.m == nil {
		return s.staticMapMember(false)
	}
	return s.m.lookup(s, &s.m.mapValue, "value")
}

func (m *mutableSchema) lookup(s *Schema, cached *atomic.Pointer[Schema], name string) *Schema {
	if v := cached.Load(); v != nil {
		return v
	}

	v, ok := m.members[name]
	if !ok {
		return nil
	}

	cached.Store(v)
	return v
}

// MemberName returns the member component of the schema's shape ID.
func (s *Schema) MemberName() string {
	if s.m == nil {
		return s.staticMemberName()
	}
	return s.m.id.Member
}

// ID returns the shape ID of the schema.
func (s *Schema) ID() ShapeID {
	if s.m == nil {
		return s.staticID()
	}
	return s.m.id
}

// TargetID returns the shape ID of the member's target shape.
func (s *Schema) TargetID() ShapeID {
	if s.m == nil {
		return s.staticTargetID()
	}
	return s.m.targetID
}

// Type returns the shape type of the schema.
func (s *Schema) Type() ShapeType {
	if s.m == nil {
		return ShapeType(s.Shape)
	}
	return s.m.typ
}

// Member returns the member schema for the given name, or nil. It does not
// retain name.
func (s *Schema) Member(name string) *Schema {
	if s.m == nil {
		return s.staticMember(name)
	}
	return s.m.members[name]
}

// Members returns the schema's members as a map of name to schema. The
// returned map must not be modified.
func (s *Schema) Members() map[string]*Schema {
	if s.m == nil {
		return s.staticMembersMap()
	}
	return s.m.members
}

// OperationSchema describes an operation, which is essentially its own schema
// with additional pointers to its input and output.
type OperationSchema struct {
	*Schema
	Input, Output *Schema

	inputStream, outputStream bool
}

// NewOperationSchema returns an OperationSchema for (input, output).
func NewOperationSchema(op, input, output *Schema) *OperationSchema {
	return &OperationSchema{
		Schema:       op,
		Input:        input,
		Output:       output,
		inputStream:  isEventStream(input),
		outputStream: isEventStream(output),
	}
}

// IsInputEventStream reports whether this is an input event stream.
func (s *OperationSchema) IsInputEventStream() bool {
	return s.inputStream
}

// IsOutputEventStream reports whether this is an output event stream.
func (s *OperationSchema) IsOutputEventStream() bool {
	return s.outputStream
}

// ServiceSchema describes a service shape.
type ServiceSchema struct {
	*Schema
	Version string
}

// NewServiceSchema returns a ServiceSchema for the given service shape.
func NewServiceSchema(schema *Schema, version string) *ServiceSchema {
	return &ServiceSchema{Schema: schema, Version: version}
}

// SchemaTrait returns the target trait on the schema if it exists.
//
// For member schemas this returns the effective trait, which is the trait
// declared directly on the member if present, else the trait inherited from
// the target shape.
func SchemaTrait[T Trait](s *Schema) (T, bool) {
	return schemaTrait[T](s, false)
}

// SchemaDirectTrait returns the target trait on the schema if it was
// declared directly on the schema.
//
// For member schemas this returns the trait only if it was declared on the
// member itself, ignoring any trait inherited from the target shape. For
// non-member schemas this is equivalent to [SchemaTrait].
func SchemaDirectTrait[T Trait](s *Schema) (T, bool) {
	return schemaTrait[T](s, true)
}

func schemaTrait[T Trait](s *Schema, directOnly bool) (T, bool) {
	var zero T

	if s == nil {
		return zero, false
	}

	if it, ok := Trait(zero).(IndexableTrait); ok {
		t := s.indexedTrait(it.TraitIndex(), directOnly)
		if t == nil {
			return zero, false
		}
		tt, ok := t.(T)
		return tt, ok
	}

	opaque, ok := s.otherTrait(zero.TraitID())
	if !ok {
		return zero, false
	}

	tt, ok := opaque.(T)
	return tt, ok
}

func (s *Schema) indexedTrait(idx int, directOnly bool) Trait {
	var indexed []Trait
	var directMask uint64
	if m := s.m; m != nil {
		indexed, directMask = m.indexed, m.directMask
	} else {
		ts := &s.set.Traits[s.Traits]
		indexed, directMask = ts.Indexed, ts.DirectMask
	}

	if idx >= len(indexed) {
		return nil
	}
	if directOnly && directMask&(1<<uint(idx)) == 0 {
		return nil
	}
	return indexed[idx]
}

func (s *Schema) otherTrait(id ShapeID) (Trait, bool) {
	if m := s.m; m != nil {
		t, ok := m.traits[id]
		return t, ok
	}

	for _, t := range s.set.Traits[s.Traits].Other {
		if t.TraitID() == id {
			return t, true
		}
	}
	return nil, false
}

// indexStreaming is the indexed trait slot for @streaming, mirrored from
// traits.indexStreaming. We can't import the traits package from here due to a
// circular dependency.
const indexStreaming = 17

func isEventStream(s *Schema) bool {
	if s == nil {
		return false
	}

	if s.m == nil {
		for i := uint32(1); i <= uint32(s.Count); i++ {
			if m := s.at(s.Target + i); m.Type() == ShapeTypeUnion && m.indexedTrait(indexStreaming, false) != nil {
				return true
			}
		}
		return false
	}

	for _, m := range s.m.members {
		if m.Type() != ShapeTypeUnion {
			continue
		}
		if m.indexedTrait(indexStreaming, false) != nil {
			return true
		}
	}
	return false
}
