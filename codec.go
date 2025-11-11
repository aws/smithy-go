package smithy

import (
	"math/big"
	"time"
)

type Codec interface {
	Serializer() ShapeSerializer
	Deserializer() ShapeDeserializer
}

// ShapeSerializer implements the marshaling of an in-code representation of a
// shape to an unspecified data format, which is determined by the
// implementation.
type ShapeSerializer interface {
	Bytes() []byte

	WriteInt8(*smithy.Schema, int8)
	WriteInt16(*smithy.Schema, int16)
	WriteInt32(*smithy.Schema, int32)
	WriteInt64(*smithy.Schema, int64)

	WriteFloat32(*smithy.Schema, float32)
	WriteFloat64(*smithy.Schema, float64)

	WriteBigInteger(*smithy.Schema, big.Int)
	WriteBigDecimal(*smithy.Schema, big.Float)

	WriteBool(*smithy.Schema, bool)
	WriteString(*smithy.Schema, string)
	WriteBlob(*smithy.Schema, []byte)
	WriteTime(*smithy.Schema, time.Time)
	WriteNil(*smithy.Schema)

	WriteList(*smithy.Schema) func()
	WriteMap(*smithy.Schema) func()
	WriteDocument(*smithy.Schema, any) // TODO value type?
}

type ShapeDeserializer interface {
}

type Serializable interface {
	Serialize(ShapeSerializer) ([]byte, error)
}

type Deserializable interface {
	Deserialize(ShapeDeserializer) error
}
