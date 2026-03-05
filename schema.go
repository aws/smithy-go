package smithy

import (
	"fmt"
	"iter"
	"maps"
	"strings"
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
func (s *ShapeID) String() string {
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
type Schema struct {
	id      ShapeID
	typ     ShapeType
	members map[string]*Schema // member name -> schema
	traits  map[string]Trait   // trait ID -> trait

	listMember       *Schema
	mapKey, mapValue *Schema
}

// NewSchema creates a new Schema with the given shape ID and traits.
func NewSchema(id ShapeID, typ ShapeType, numMembers int, traits ...Trait) *Schema {
	traitMap := make(map[string]Trait, len(traits))
	for _, t := range traits {
		traitMap[t.TraitID()] = t
	}
	return &Schema{
		id:      id,
		typ:     typ,
		members: make(map[string]*Schema, numMembers),
		traits:  traitMap,
	}
}

// AddMember adds a member to the schema derived from the target, with
// optional trait overrides. The member schema is returned for caller
// reference.
func (s *Schema) AddMember(name string, target *Schema, traits ...Trait) *Schema {
	m := &Schema{
		id:         ShapeID{Member: name},
		typ:        target.typ,
		members:    target.members,
		traits:     maps.Clone(target.traits),
		listMember: target.listMember,
		mapKey:     target.mapKey,
		mapValue:   target.mapValue,
	}

	if len(m.traits) == 0 && len(traits) != 0 {
		m.traits = map[string]Trait{}
	}
	for _, t := range traits {
		m.traits[t.TraitID()] = t
	}

	s.members[name] = m
	switch name {
	case "member":
		s.listMember = m
	case "key":
		s.mapKey = m
	case "value":
		s.mapValue = m
	}
	return m
}

// ListMember returns the "member" schema for list types.
func (s *Schema) ListMember() *Schema {
	return s.listMember
}

// MapKey returns the "key" schema for map types.
func (s *Schema) MapKey() *Schema {
	return s.mapKey
}

// MapValue returns the "value" schema for map types.
func (s *Schema) MapValue() *Schema {
	return s.mapValue
}

// MemberName returns the member component of the schema's shape ID.
func (s *Schema) MemberName() string {
	return s.id.Member
}

// Member returns the member schema for the given name, or nil.
func (s *Schema) Member(name string) *Schema {
	return s.members[name]
}

// Members returns an iterator over the schema's members as (name, schema)
// pairs.
func (s *Schema) Members() iter.Seq2[string, *Schema] {
	return func(yield func(string, *Schema) bool) {
		for name, member := range s.members {
			if !yield(name, member) {
				return
			}
		}
	}
}

// Trait returns the target trait on the schema if it exists.
func SchemaTrait[T Trait](s *Schema) (T, bool) {
	var trait T

	if s == nil {
		return trait, false
	}

	opaque, ok := s.traits[trait.TraitID()]
	if !ok {
		return trait, false
	}

	tt, ok := opaque.(T)
	return tt, ok
}
