package json

import (
	"github.com/aws/smithy-go"
)

// Codec is a JSON codec.
type Codec struct{}

var _ smithy.Codec = (*Codec)(nil)

// Serializer returns a JSON shape serializer.
func (c *Codec) Serializer() smithy.ShapeSerializer {
	return NewShapeSerializer()
}

// Deserializer returns a JSON shape deserializer.
func (c *Codec) Deserializer(p []byte) smithy.ShapeDeserializer {
	return NewShapeDeserializer(p)
}
