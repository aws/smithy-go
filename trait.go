package smithy

// Trait represents a trait applied to a shape in a Smithy model. Traits
// related to (de)serialization are included in code-generated Schemas for the
// client.
type Trait interface {
	TraitID() string
}

// IndexableTrait is optionally implemented by Trait values that have a
// reserved index in Schema's indexed trait slice. All traits defined in the
// traits package implement this interface.
type IndexableTrait interface {
	Trait
	TraitIndex() int
}
