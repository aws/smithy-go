package smithy

import (
	"fmt"
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
	ID      ShapeID
	Type    ShapeType
	Members map[string]*Schema // member name -> schema
	Traits  map[string]Trait   // trait ID -> trait
}

// NewMember creates a member schema from a target schema, overriding traits.
//
// Traits provided for the member override any traits on the target if there
// is collision.
func NewMember(name string, target *Schema, traits ...Trait) *Schema {
	m := &Schema{
		ID:      ShapeID{Member: name},
		Type:    target.Type,
		Members: target.Members,
		Traits:  maps.Clone(target.Traits),
	}

	if len(m.Traits) == 0 && len(traits) != 0 {
		m.Traits = map[string]Trait{}
	}
	for _, t := range traits {
		m.Traits[t.TraitID()] = t
	}

	return m
}

// Trait returns the target trait on the schema if it exists.
func SchemaTrait[T Trait](s *Schema) (T, bool) {
	var trait T

	opaque, ok := s.Traits[trait.TraitID()]
	if !ok {
		return trait, false
	}

	tt, ok := opaque.(T)
	return tt, ok
}
