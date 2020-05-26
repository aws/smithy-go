package document

import (
	"bytes"
	"encoding/json"
	"io"
)

// JSON provides an abstract representation of the serialized JSON document
// based. The Unmarshal method will attempt to unmarshal the underlying
// document's value into the Go type provided.
type JSON interface {
	UnmarshalDocument(interface{}) error
	JSONReader() io.Reader
}

// RawJSON provides a document marshaler for a byte slice containing a
// JSON document.
type RawJSON []byte

// MarshalJSONDocument attempts to marshal the Go value into a JSON document.
// Returns a marshaled raw JSON document value, or error if marshaling failed.
func MarshalJSONDocument(v interface{}) (RawJSON, error) {
	b, err := json.Marshal(v)
	if err != nil {
		return nil, err
	}
	return RawJSON(b), nil
}

// UnmarshalDocument attempts to unmarshal the JSON document into the Go type
// provided. The Go type must be a pointer value type, or the method will
// panic. Returns an error if the document could not be unmarshalled.
func (d RawJSON) UnmarshalDocument(t interface{}) error {
	return json.Unmarshal(d, t)
}

// JSONReader returns an io.Reader for reading the JSON document's
// serialized bytes.
func (d RawJSON) JSONReader() io.Reader {
	return bytes.NewReader(d)
}

// JSONReader provides a DocumentMarshaler for a JSON document from an
// io.Reader value.
type JSONReader struct {
	r io.Reader
}

// NewJSONReader returns a JSON document unmarshaller for an io.Reader value.
func NewJSONReader(r io.Reader) *JSONReader {
	return &JSONReader{r: r}
}

// Close will close the underlying reader if it is an io.Closer, otherwise this
// method does nothing.
func (d *JSONReader) Close() error {
	if v, ok := d.r.(io.Closer); ok {
		return v.Close()
	}
	return nil
}

// UnmarshalDocument attempts to unmarshal the JSON document into the Go type
// provided. The Go type must be a pointer value type, or the method will
// panic. Returns an error if the document could not be unmarshalled.
//
// Calling this method multiple types most likely will result in EOF, or other
// errors.
func (d *JSONReader) UnmarshalDocument(t interface{}) error {
	// TODO should the document unmarshal call close?
	defer d.Close()

	return json.NewDecoder(d.r).Decode(t)
}

// JSONReader returns an io.Reader for reading the JSON document's
// serialized bytes.
//
// Only UnmasrhalDocument or JSONReader methods should be used, not both.
// Since they share the same underlying reader, using both methods will result
// in undefined behavior.
func (d *JSONReader) JSONReader() io.Reader {
	return d.r
}
