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
	WriteInt8Ptr(*Schema, *int8)
	WriteInt16Ptr(*Schema, *int16)
	WriteInt32Ptr(*Schema, *int32)
	WriteInt64Ptr(*Schema, *int64)

	WriteFloat32(*Schema, float32)
	WriteFloat64(*Schema, float64)
	WriteFloat32Ptr(*Schema, *float32)
	WriteFloat64Ptr(*Schema, *float64)

	WriteBool(*Schema, bool)
	WriteBoolPtr(*Schema, *bool)

	WriteString(*Schema, string)
	WriteStringPtr(*Schema, *string)

	WriteBigInteger(*Schema, big.Int)
	WriteBigDecimal(*Schema, big.Float)
	WriteBlob(*Schema, []byte)
	WriteTime(*Schema, time.Time)
	WriteNil(*Schema)
	WriteList(*Schema, func())

	WriteMap(*Schema, func())
	WriteKey(*Schema, string, func())
}

type ShapeDeserializer interface {
	ReadInt8(*Schema) (int8, error)
	ReadInt16(*Schema) (int16, error)
	ReadInt32(*Schema) (int32, error)
	ReadInt64(*Schema) (int64, error)

	ReadFloat32(*Schema) (float32, error)
	ReadFloat64(*Schema) (float64, error)

	ReadBool(*Schema) (bool, error)
	ReadString(*Schema) (string, error)

	ReadList(*Schema, func() error) error
	ReadMap(*Schema, func(string) error) error
}

type Serializable interface {
	Serialize(ShapeSerializer) ([]byte, error)
}

type Deserializable interface {
	Deserialize(ShapeDeserializer) error
}
