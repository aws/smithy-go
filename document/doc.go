// Package document provides utilities to marshal and unmarshal Smithy Document
// types to an from Go types.
package document

// Unmarshaler provides an abstract representation of document based
// values like JSON, Ion and XML. The Unmarshal method will attempt to
// unmarshal the underlying document's value into the Go type provided.
type Unmarshaler interface {
	UnmarshalDocument(interface{}) error
}
