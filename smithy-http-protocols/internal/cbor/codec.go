package cbor

import (
	"github.com/aws/smithy-go"
)

// Codec is a CBOR codec.
type Codec struct{}

var _ smithy.Codec = (*Codec)(nil)

// Serializer returns a CBOR shape serializer.
func (c *Codec) Serializer() smithy.ShapeSerializer {
	return NewShapeSerializer()
}

// Deserializer returns a CBOR shape deserializer.
func (c *Codec) Deserializer(p []byte) smithy.ShapeDeserializer {
	return NewShapeDeserializer(p)
}
