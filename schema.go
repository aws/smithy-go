package smithy

import "strings"

// Schema encodes information about a shape from a Smithy model.
//
// Generated clients use schemas at runtime to dynamically (de)serialize
// request/responses.
type Schema struct {
	shapeID    string
	memberName string // only if member

	members map[string]Schema // member name -> schema
	traits  map[string]Trait  // trait ID -> trait
}

// NewSchema returns a schema with the provided members and traits.
//
// Generated clients include schemas for every shape that needs to be
// (de)serialized as part of a service operation.
func NewSchema(id string, members map[string]Schema, trait ...Trait) Schema {
	traits := map[string]Trait{}
	for _, t := range trait {
		traits[t.TraitID()] = t
	}

	return Schema{
		shapeID: id,
		traits:  traits,
		members: members,
	}
}

// NewMemberSchema returns a member schema with the provided members and traits.
func NewMemberSchema(name string, trait ...Trait) Schema {
	traits := map[string]Trait{}
	for _, t := range trait {
		traits[t.TraitID()] = t
	}

	return Schema{
		memberName: name,
		traits:     traits,
	}
}

// ShapeID returns the shape ID for this schema as it appears in the original
// Smithy model.
func (s *Schema) ShapeID() string {
	return s.shapeID
}

// ShapeName returns the unqualified shape name for this schema.
func (s *Schema) ShapeName() string {
	parts := strings.Split(s.shapeID, "#")
	return parts[1]
}

// MemberName returns the member name for this schema as it appears in the
// Smithy model.
func (s *Schema) MemberName() string {
	return s.memberName
}

// Member returns the named member of the schema if it exists.
func (s *Schema) Member(name string) (*Schema, bool) {
	schema, ok := s.members[name]
	if !ok {
		return nil, false
	}
	return &schema, true
}

// Trait returns the target trait on the schema if it exists.
func SchemaTrait[T Trait](s Schema) (T, bool) {
	var trait T

	opaque, ok := s.traits[trait.TraitID()]
	if !ok {
		return trait, false
	}

	if tt, ok := opaque.(T); ok {
		trait = tt
	}
	return trait, true
}
