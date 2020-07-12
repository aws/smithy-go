package xml

import (
	"bytes"
)

// Encoder is an XML encoder that supports construction of XML values
// using methods.
type Encoder struct {
	w *bytes.Buffer
	Value
}

// NewEncoder returns an XML encoder
func NewEncoder() *Encoder {
	writer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	return &Encoder{w: writer, Value: newValue(writer, &scratch)}
}

// String returns the string output of the XML encoder
func (e Encoder) String() string {
	return e.w.String()
}

// Bytes returns the []byte slice of the XML encoder
func (e Encoder) Bytes() []byte {
	return e.w.Bytes()
}
