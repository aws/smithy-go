package json

import "github.com/aws/smithy-go"

// Codec implements [smithy.Codec] for JSON. The zero value is a valid codec.
type Codec struct {
	Options CodecOptions
}

var _ smithy.Codec = Codec{}

// Serializer returns a [smithy.ShapeSerializer] for JSON.
//
// The returned value may implement interface{ Close() }. Callers should call
// Close when finished with the serializer to allow it to be reused.
func (c Codec) Serializer() smithy.ShapeSerializer {
	return newShapeSerializer(c.Options)
}

// Deserializer returns a [smithy.ShapeDeserializer] for JSON that reads p
// directly. p is not copied and MUST NOT be modified while the returned
// deserializer is in use.
//
// The returned value may implement interface{ Close() }. Callers should call
// Close when finished with the deserializer to allow it to be reused.
func (c Codec) Deserializer(p []byte) smithy.ShapeDeserializer {
	return newShapeDeserializer(p, c.Options)
}

// Marshal serializes v to JSON.
func (c Codec) Marshal(v smithy.Serializable) ([]byte, error) {
	s := newShapeSerializer(c.Options)
	defer s.Close()

	v.Serialize(s)
	return s.Bytes(), nil
}

// Unmarshal deserializes v from JSON.
func (c Codec) Unmarshal(p []byte, v smithy.Deserializable) error {
	d := newShapeDeserializer(p, c.Options)
	defer d.Close()

	return v.Deserialize(d)
}
