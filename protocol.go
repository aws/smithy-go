package smithy

import (
	"context"
	"math/big"
	"time"
)

// ClientProtocol defines the interface through which client-side operation
// request/responses are (de)serialized across the wire.
//
// TRequest and TResponse represent the input and output transport types for
// the protocol. In most cases this corresponds to *smithyhttp.Request and
// *smithyhttp.Response.
//
// While a caller CAN define their own protocol, it is almost never necessary
// to do so. In practice, a generated client will utilize one of the predefined
// protocols implemented as part of the Smithy client runtime.
type ClientProtocol[TRequest, TResponse any] interface {
	ID() string
	SerializeRequest(context.Context, Serializable, TRequest) error

	// DeserializeResponse deserializes the transport response into the modeled
	DeserializeResponse(ctx context.Context, types *TypeRegistry, resp TResponse, out Deserializable) error
}

// Codec provides implementations of Serializer and ShapeDeserializer to be
// used by a Protocol.
type Codec interface {
	Serializer() ShapeSerializer
	Deserializer([]byte) ShapeDeserializer
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
	WriteTimePtr(*Schema, *time.Time)

	WriteStruct(*Schema, Serializable)

	WriteUnion(schema, variant *Schema, v Serializable)

	WriteDocument(*Schema, Document2)

	WriteNil(*Schema)

	WriteList(*Schema)
	CloseList()

	WriteMap(*Schema)
	WriteKey(*Schema, string)
	CloseMap()
}

// ShapeSerializer implements the unmarshaling from some unspecified data
// format to an encoded shape.
type ShapeDeserializer interface {
	ReadInt8(*Schema, *int8) error
	ReadInt16(*Schema, *int16) error
	ReadInt32(*Schema, *int32) error
	ReadInt64(*Schema, *int64) error

	ReadInt8Ptr(*Schema, **int8) error
	ReadInt16Ptr(*Schema, **int16) error
	ReadInt32Ptr(*Schema, **int32) error
	ReadInt64Ptr(*Schema, **int64) error

	ReadFloat32(*Schema, *float32) error
	ReadFloat64(*Schema, *float64) error

	ReadFloat32Ptr(*Schema, **float32) error
	ReadFloat64Ptr(*Schema, **float64) error

	ReadBool(*Schema, *bool) error
	ReadBoolPtr(*Schema, **bool) error

	ReadString(*Schema, *string) error
	ReadStringPtr(*Schema, **string) error

	ReadTime(*Schema, *time.Time) error
	ReadTimePtr(*Schema, **time.Time) error

	ReadBlob(*Schema, *[]byte) error

	ReadList(*Schema) error
	// returns true if there's another item in the list, false at the end and
	// an error if a decode error is encountered. use other deserializer
	// methods to read the expected type from the deserializer
	ReadListItem(*Schema) (bool, error)

	ReadMap(*Schema) error
	// the bool will be true if there's another key in the list and the string
	// will have the value of that key, with any decode error in the error. use
	// other deserializer methods to read the expected type.
	ReadMapKey(*Schema) (string, bool, error)

	ReadStruct(*Schema) error
	// returns the member schema for the given struct, nil when there are no
	// more members, with any decode error in the error. use other deserializer
	// methods to read the expected type.
	ReadStructMember() (*Schema, error)

	// returns the schema for the variant that the union is
	ReadUnion(*Schema) (*Schema, error)

	ReadDocument(*Schema, *Document2) error
}

// Serializable is an entity that can describe itself to a ShapeSerializer to
// be encoded to some format.
//
// Unlike the standard library marshaler interfaces, which idiomatically encode
// to []byte, the output format and data type here is not specified at all.
// This is because Smithy shapes need to encode to a variety of formats or data
// carriers. For example, HTTP-binding JSON protocols need to serialize some
// members to bytes (the HTTP request body) and others directly to fields on
// the HTTP request itself (e.g. headers).
type Serializable interface {
	Serialize(ShapeSerializer)
}

// Deserializable is an entity that can unmarshal itself from a
// ShapeDeserializer.
type Deserializable interface {
	Deserialize(ShapeDeserializer) error
}

// DeserializableError is implemented by modeled error types for a service.
type DeserializableError interface {
	Deserializable
	error
}

// ReadStruct is a utility API for generated clients.
func ReadStruct(d ShapeDeserializer, schema *Schema, memberFn func(*Schema) error) error {
	if err := d.ReadStruct(schema); err != nil {
		return err
	}

	for {
		ms, err := d.ReadStructMember()
		if ms == nil {
			return nil
		}

		if err != nil {
			return err
		}

		if err := memberFn(ms); err != nil {
			return err
		}
	}

	return nil
}

// ReadList is a utility API for generated clients.
func ReadList(d ShapeDeserializer, schema *Schema, memberFn func() error) error {
	if err := d.ReadList(schema); err != nil {
		return err
	}

	for {
		ok, err := d.ReadListItem(schema.Members["member"]) // TODO
		if !ok {
			return nil
		}
		if err != nil {
			return err
		}

		if err := memberFn(); err != nil {
			return err
		}
	}

	return nil
}

// ReadMap is a utility API for generated clients.
func ReadMap(d ShapeDeserializer, schema *Schema, memberFn func(string) error) error {
	if err := d.ReadMap(schema); err != nil {
		return err
	}

	for {
		k, ok, err := d.ReadMapKey(schema.Members["key"]) // TODO
		if !ok {
			return nil
		}
		if err != nil {
			return err
		}

		if err := memberFn(k); err != nil {
			return err
		}
	}

	return nil
}
