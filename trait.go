package smithy

// Trait represents a trait applied to a shape in a Smithy model. Traits
// related to (de)serialization are included in code-generated Schemas for the
// client.
type Trait interface {
	TraitID() string // TODO(serde2): should return a ShapeID
}

// TODO(serde2): investigate performance tradeoff of using an "indexed" map for
// the known traits (basically the ones defined here) since the rest- and
// xml-based protocols do a ton of trait lookup (which translates to map lookup)
