package smithy

// A document type represents an untyped JSON-like value.
//
// Not all protocols support document types, and the serialization format of a
// document type is protocol specific. All JSON protocols SHOULD support
// document types and they SHOULD serialize document types inline as normal
// JSON values.
type Document interface {
	// Marshal will attempt to serialize the provided type into a
	// protocol specific document value.
	MarshalDocument(x interface{}) error

	// Unmarshall will attempt to map the protocol specific document value
	// onto the provided type. If the type does not match the document type
	// an error will be returned.
	UnmarshalDocument(x interface{}) error

	// Indicates the document is a null (nil) type.
	IsNull() bool

	// Indicates the document is a boolean (bool) type.
	IsBoolean() bool

	// Indicates the document is a string type.
	IsString() bool

	// Indicates the document is a byte type.
	IsByte() bool

	// Indicates the document is a short (int16) type.
	IsShort() bool

	// Indicates the document is an integer (int32) type.
	IsInteger() bool

	// Indicates the document is a long (int64) type.
	IsLong() bool

	// Indicates the document is a float (float32) type.
	IsFloat() bool

	// Indicates the document is a double (float64) type.
	IsDouble() bool

	// Indicates the document is an array.
	IsArray() bool

	// Indicates the document is a map. Keys for document maps MUST be
	// strings.
	IsMap() bool
}
