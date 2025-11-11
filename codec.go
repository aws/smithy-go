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

	WriteInt8(*Schema, int8)
	WriteInt16(*Schema, int16)
	WriteInt32(*Schema, int32)
	WriteInt64(*Schema, int64)

	WriteFloat32(*Schema, float32)
	WriteFloat64(*Schema, float64)

	WriteBigInteger(*Schema, big.Int)
	WriteBigDecimal(*Schema, big.Float)

	WriteBool(*Schema, bool)
	WriteString(*Schema, string)
	WriteBlob(*Schema, []byte)
	WriteTime(*Schema, time.Time)
	WriteNil(*Schema)

	WriteList(*Schema) func()
	WriteMap(*Schema) func()
	WriteDocument(*Schema, any) // TODO value type?
}

type ShapeDeserializer interface {
}

type Serializable interface {
	Serialize(ShapeSerializer) ([]byte, error)
}

type Deserializable interface {
	Deserialize(ShapeDeserializer) error
}
