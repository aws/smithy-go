package smithy

import (
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
	id  ShapeID
	typ ShapeType

	members map[string]*Schema // member name -> schema
	traits  map[string]Trait   // trait ID -> trait
}

// SchemaOptions configures a new Schema.
type SchemaOptions struct {
	members []*Schema
	traits  []Trait
}

// WithMember adds a member targeting the given Schema.
//
// Traits provided for the member here override any traits on the target if
// there is collision.
func WithMember(name string, target *Schema, traits ...Trait) func(*SchemaOptions) {
	return func(o *SchemaOptions) {
		m := &Schema{
			id:      ShapeID{Member: name},
			typ:     target.typ,
			members: target.members,
			traits:  maps.Clone(target.traits),
		}

		for _, t := range traits {
			m.traits[t.TraitID()] = t
		}

		o.members = append(o.members, m)
	}
}

// WithTraits adds traits to the Schema.
func WithTraits(traits ...Trait) func(*SchemaOptions) {
	return func(o *SchemaOptions) {
		o.traits = append(o.traits, traits...)
	}
}

// NewSchema returns a schema with the provided members and traits.
//
// Generated clients include schemas for every shape that needs to be
// (de)serialized as part of a service operation in a schemas/ package.
func NewSchema(id string, typ ShapeType, opts ...func(*SchemaOptions)) *Schema {
	var o SchemaOptions
	for _, opt := range opts {
		opt(&o)
	}

	sid := stoid(id)
	members := make(map[string]*Schema, len(o.members))
	for _, m := range o.members {
		m.id.Namespace = sid.Namespace
		m.id.Name = sid.Name
		members[m.id.Member] = m
	}

	traits := make(map[string]Trait, len(o.traits))
	for _, t := range o.traits {
		traits[t.TraitID()] = t
	}

	return &Schema{
		id:      sid,
		typ:     typ,
		members: members,
		traits:  traits,
	}
}

// ID returns the shape ID for this schema as it appears in the original
// Smithy model.
func (s *Schema) ID() ShapeID {
	return s.id
}

// Type returns the schema's type.
func (s *Schema) Type() ShapeType {
	return s.typ
}

// Member returns the named member from the schema.
func (s *Schema) Member(name string) *Schema {
	m, _ := s.members[name]
	return m
}

// Trait returns the target trait on the schema if it exists.
func SchemaTrait[T Trait](s *Schema) (T, bool) {
	var trait T

	opaque, ok := s.traits[trait.TraitID()]
	if !ok {
		return trait, false
	}

	tt, ok := opaque.(T)
	return tt, ok
}
