package smithy

// Document provides access to loosely structured data in a document-like
// format.
//
// Deprecated: see Document2.
type Document interface {
	UnmarshalDocument(interface{}) error
	GetValue() (interface{}, error)
}

// Document2 provides access to loosely structured data in a document-like
// format.
//
// This is the common interface used by ShapeSerializer and ShapeDeserializer to
// go between arbitrary opaque types and arbitrary data formats.
type Document2 interface {
	Value() any
	UnmarshalDocument(any) error
}
