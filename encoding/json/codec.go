package json

import (
	"github.com/aws/smithy-go"
)

// Codec is a JSON codec.
type Codec struct {
	// Whether to respect smithy.api#jsonName on member shapes.
	UseJSONName bool
}

var _ smithy.Codec = (*Codec)(nil)

// Serializer returns a JSON shape serializer.
func (c *Codec) Serializer() smithy.ShapeSerializer {
	return &ShapeSerializer{
		root: NewEncoder(),
		head: stack{},
	}
}

// Deserializer returns a JSON shape deserializer.
func (c *Codec) Deserializer(p []byte) smithy.ShapeDeserializer {
	return NewShapeDeserializer(p)
}

type stack struct {
	values []any
}

type empty struct{}

func (s *stack) Top() any {
	if len(s.values) == 0 {
		return empty{}
	}
	return s.values[len(s.values)-1]
}

func (s *stack) Push(v any) {
	s.values = append(s.values, v)
}

func (s *stack) Pop() {
	s.values = s.values[:len(s.values)-1]
}

func (s *stack) Len() int {
	return len(s.values)
}
