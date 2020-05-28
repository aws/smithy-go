package document

import "io"

// Embedded provides an abstract representation of a serialized document value.
type Embedded interface {
	// Attempts to unmarshal the serialized document. If the media type is
	// unknown, or the unmarshal fails, an error will be returned.
	//
	// Returns MediaTypeUnknownError if the media type of the document is
	// unknown.
	UnmarshalDocument(interface{}) error

	// Returns the media type of the document.
	MediaType() string

	// Returns a reader to get the bytes of the serialized document.
	DocumentReader() io.Reader
}
