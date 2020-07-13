package xml

import (
	"bytes"
)

// General usage: Value is responsible for writing start tag, close tag for an xml element.
// * If a certain value operation returns a close function.
//   The close function must ideally be called with defer.
//
// * This utility is written in accordance to our design to delegate to shape serializer function
// 	 in which a xml.Value will be passed around.
//
// * Resources followed: https://awslabs.github.io/smithy/1.0/spec/core/xml-traits.html#

// Encoder is an XML encoder that supports construction of XML values
// using methods.
type Encoder struct {
	w *bytes.Buffer
	Value
}

// noOpFn prevents panics from unexpected defer statements
var noOpFn = func() {}

// NewEncoder returns an XML encoder
func NewEncoder() *Encoder {
	writer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	return &Encoder{w: writer, Value: newValue(writer, &scratch, noOpFn, noOpFn)}
}

// String returns the string output of the XML encoder
func (e Encoder) String() string {
	return e.w.String()
}

// Bytes returns the []byte slice of the XML encoder
func (e Encoder) Bytes() []byte {
	return e.w.Bytes()
}
