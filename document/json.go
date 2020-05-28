package document

import (
	"bytes"
	"encoding/json"
	"io"
)

// LazyJSONValue provides a wrapper for a Go value that will be lazily serialized as
// a JSON document.
type LazyJSONValue struct {
	LazyValue
}

// NewLazyJSONValue returns an initialized LazyJSONValue wrapping the provided
// Go value.
func NewLazyJSONValue(v interface{}) LazyJSONValue {
	return LazyJSONValue{
		LazyValue: NewLazyValue(v),
	}
}

// MediaType returns the document's media type.
func (LazyJSONValue) MediaType() string { return "application/json" }

// DocumentReader returns an io.Reader for reading the JSON document's
// serialized bytes.
func (d LazyJSONValue) DocumentReader() io.Reader {
	r, w := io.Pipe()
	go func() {
		encoder := json.NewEncoder(w)
		if err := encoder.Encode(d.Value); err != nil && err != io.EOF {
			w.CloseWithError(err)
		}
	}()

	return r
}

// JSONBytes provides a document marshaler for a byte slice containing a
// JSON document.
type JSONBytes []byte

// MarshalJSONBytes attempts to marshal the Go value into a JSON document.
// Returns a marshaled raw JSON document value, or error if marshaling failed.
func MarshalJSONBytes(v interface{}) (JSONBytes, error) {
	b, err := json.Marshal(v)
	if err != nil {
		return nil, err
	}
	return JSONBytes(b), nil
}

// UnmarshalDocument attempts to unmarshal the JSON document into the Go type
// provided. The Go type must be a pointer value type, or the method will
// panic. Returns an error if the document could not be unmarshalled.
func (d JSONBytes) UnmarshalDocument(t interface{}) error {
	// TODO need document type generic unmarshaling behavior.
	return json.Unmarshal(d, t)
}

// GetValue returns the unmarshalled JSON document as generic Go value,
// interface{}. Use reflection inspect the value's contents.
func (d JSONBytes) GetValue() (interface{}, error) {
	var v interface{}
	if err := d.UnmarshalDocument(&v); err != nil {
		return nil, err
	}

	return v, nil
}

// MediaType returns the document's media type.
func (JSONBytes) MediaType() string { return "application/json" }

// DocumentReader returns an io.Reader for reading the JSON document's
// serialized bytes.
func (d JSONBytes) DocumentReader() io.Reader {
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

// GetValue returns the unmarshalled JSON document as generic Go value,
// interface{}. Use reflection inspect the value's contents.
func (d *JSONReader) GetValue() (interface{}, error) {
	var v interface{}
	if err := d.UnmarshalDocument(&v); err != nil {
		return nil, err
	}

	return v, nil
}

// MediaType returns the document's media type.
func (*JSONReader) MediaType() string { return "application/json" }

// DocumentReader returns an io.Reader for reading the JSON document's
// serialized bytes.
//
// Only UnmasrhalDocument or JSONReader methods should be used, not both.
// Since they share the same underlying reader, using both methods will result
// in undefined behavior.
func (d *JSONReader) DocumentReader() io.Reader {
	return d.r
}

// Close will close the underlying reader if it is an io.Closer, otherwise this
// method does nothing.
func (d *JSONReader) Close() error {
	if v, ok := d.r.(io.Closer); ok {
		return v.Close()
	}
	return nil
}
