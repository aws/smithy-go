package xml

import (
	"github.com/aws/smithy-go"
)

// Codec implements [smithy.Codec] for XML.
type Codec struct {
	opts []func(*ShapeSerializerOptions)
}

// NewCodec returns an XML codec with the given serializer options.
func NewCodec(opts ...func(*ShapeSerializerOptions)) *Codec {
	return &Codec{opts: opts}
}

var _ smithy.Codec = (*Codec)(nil)

// Serializer returns an XML shape serializer.
func (c *Codec) Serializer() smithy.ShapeSerializer {
	return NewShapeSerializer(c.opts...)
}

// Deserializer returns an XML shape deserializer.
func (c *Codec) Deserializer(p []byte) smithy.ShapeDeserializer {
	return NewShapeDeserializer(p)
}
