package smithy

import (
	"fmt"
	"strings"
	"sync/atomic"
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
// Generated code declares its schemas as package-level variables built with
// [NewStaticSchema], which the compiler lays out in the binary's data segment
// instead of allocating them at startup. Schemas built with [NewSchema] and
// [Schema.AddMember] are allocated on the heap. Both behave the same.
type Schema struct {
	d StaticSchema
}

// heapSchema lets a schema built with NewSchema share one allocation with the
// state it points to.
type heapSchema struct {
	Schema
	ext     SchemaExtensions
	id      ShapeID
	members SchemaMembers
	traits  TraitSet
}

// heapMember is the allocation behind a schema built with AddMember.
type heapMember struct {
	Schema
	ext    SchemaExtensions
	traits TraitSet
}

// NewSchema creates a new Schema with the given shape ID and traits.
func NewSchema(id ShapeID, typ ShapeType, numMembers int, ts ...Trait) *Schema {
	h := &heapSchema{id: id}
	h.members.byName = make(map[string]*Schema, numMembers)
	h.d = StaticSchema{
		ID:      &h.id,
		Members: &h.members,
		Traits:  &h.traits,
		Type:    typ,
		Ext:     &h.ext,
	}
	for _, t := range ts {
		h.traits.add(t, true)
	}
	return &h.Schema
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
// The target may be any schema, but AddMember panics if s was declared with
// [NewStaticSchema].
func (s *Schema) AddMember(name string, target *Schema, ts ...Trait) *Schema {
	ms := s.d.Members
	if ms == nil || ms.byName == nil {
		panic("smithy: AddMember on a static schema")
	}

	m := &heapMember{}
	if t := target.d.Traits; t != nil {
		m.traits.Indexed = cloneTraits(t.Indexed)
		m.traits.Other = cloneTraits(t.Other)
	}
	m.d = StaticSchema{
		Member:  name,
		ID:      target.d.ID,
		Members: target.d.Members,
		Traits:  &m.traits,
		Type:    target.d.Type,
		Ext:     &m.ext,
	}

	// member-declared traits override and are direct
	for _, t := range ts {
		m.traits.add(t, true)
	}

	ms.add(s.d.Type, &m.Schema)

	// Invalidate cached extensions, schema structure changed.
	for i := range s.d.Ext {
		atomic.StorePointer(&s.d.Ext[i], nil)
	}

	return &m.Schema
}

func cloneTraits(src []Trait) []Trait {
	if src == nil {
		return nil
	}
	dst := make([]Trait, len(src))
	copy(dst, src)
	return dst
}

// ListMember returns the "member" schema for list types.
func (s *Schema) ListMember() *Schema {
	if t := s.d.Type; t != ShapeTypeList && t != ShapeTypeSet {
		return nil
	}
	return s.d.Members.at(0)
}

// MapKey returns the "key" schema for map types.
func (s *Schema) MapKey() *Schema {
	if s.d.Type != ShapeTypeMap {
		return nil
	}
	return s.d.Members.at(0)
}

// MapValue returns the "value" schema for map types.
func (s *Schema) MapValue() *Schema {
	if s.d.Type != ShapeTypeMap {
		return nil
	}
	return s.d.Members.at(1)
}

// MemberName returns the member component of the schema's shape ID.
func (s *Schema) MemberName() string {
	return s.d.Member
}

// ID returns the shape ID of the schema.
func (s *Schema) ID() ShapeID {
	if s.d.Member != "" {
		return ShapeID{Member: s.d.Member}
	}
	if s.d.ID == nil {
		return ShapeID{}
	}
	return *s.d.ID
}

// TargetID returns the shape ID of the member's target shape.
func (s *Schema) TargetID() ShapeID {
	if s.d.Member == "" || s.d.ID == nil {
		return ShapeID{}
	}
	return *s.d.ID
}

// Type returns the shape type of the schema.
func (s *Schema) Type() ShapeType {
	return s.d.Type
}

// Member returns the member schema for the given name, or nil. It does not
// retain name.
func (s *Schema) Member(name string) *Schema {
	if ms := s.d.Members; ms != nil {
		return ms.lookup(name)
	}
	return nil
}

// Members returns the schema's members as a map of name to schema. The
// returned map must not be modified.
func (s *Schema) Members() map[string]*Schema {
	ms := s.d.Members
	if ms == nil {
		return nil
	}

	if ms.byName != nil {
		return ms.byName
	}
	if p := ms.cache.Load(); p != nil {
		return *p
	}
	return ms.buildMap()
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

	opaque := s.d.Traits.other(zero.TraitID())
	if opaque == nil {
		return zero, false
	}

	tt, ok := opaque.(T)
	return tt, ok
}

func (s *Schema) indexedTrait(idx int, directOnly bool) Trait {
	ts := s.d.Traits
	if ts == nil || idx >= len(ts.Indexed) {
		return nil
	}
	if directOnly && ts.DirectMask&(1<<uint(idx)) == 0 {
		return nil
	}
	return ts.Indexed[idx]
}

// indexStreaming is the indexed trait slot for @streaming, mirrored from
// traits.indexStreaming. We can't import the traits package from here due to a
// circular dependency.
const indexStreaming = 17

func isEventStream(s *Schema) bool {
	if s == nil || s.d.Members == nil {
		return false
	}
	for _, m := range s.d.Members.List {
		if m.Type() == ShapeTypeUnion && m.indexedTrait(indexStreaming, false) != nil {
			return true
		}
	}
	return false
}
