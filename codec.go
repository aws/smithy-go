package smithy

import "fmt"

// Codec pairs a ShapeSerializer and ShapeDeserializer for a byte-oriented
// serialization format.
//
// Deserializer takes the encoded payload as a byte slice. Every format Smithy
// models today is decoded from a complete payload rather than incrementally,
// and callers invariably hold one, so binding the source at construction
// avoids a copy. Implementations MAY retain the slice, so callers MUST NOT
// modify it while the returned ShapeDeserializer is in use.
//
// A Codec is concerned only with the data format. It MUST NOT create
// protocol framing such as HTTP messages.
type Codec interface {
	Serializer() ShapeSerializer
	Deserializer(p []byte) ShapeDeserializer
}

// UnionVariantSerializer is a utility API for generated clients. It adapts a
// single union variant to Serializable, wrapping the variant value in the
// union envelope.
type UnionVariantSerializer struct {
	Union   *Schema
	Variant *Schema
	Value   Serializable
}

// Serialize implements Serializable.
func (v UnionVariantSerializer) Serialize(s ShapeSerializer) {
	s.WriteUnion(v.Union, v.Variant)
	v.Value.Serialize(s)
	s.CloseUnion()
}

// UnionVariantDeserializer is a utility API for generated clients. It adapts
// a single union variant to Deserializable, unwrapping the union envelope and
// verifying that the encoded variant is the expected one.
type UnionVariantDeserializer struct {
	Union   *Schema
	Variant *Schema
	Value   Deserializable
}

// Deserialize implements Deserializable.
func (v UnionVariantDeserializer) Deserialize(d ShapeDeserializer) error {
	ms, err := d.ReadUnion(v.Union)
	if err != nil {
		return err
	}
	if ms == nil {
		return fmt.Errorf("union %s has no variant set", v.Union.ID())
	}
	if ms != v.Variant {
		return fmt.Errorf("expected union variant %s, got %s",
			v.Variant.MemberName(), ms.MemberName())
	}

	return v.Value.Deserialize(d)
}
