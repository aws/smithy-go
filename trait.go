package smithy

// Trait represents a trait applied to a shape in a Smithy model. Traits
// related to (de)serialization are included in code-generated Schemas for the
// client.
type Trait interface {
	TraitID() string
}
