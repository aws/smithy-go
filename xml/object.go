package xml

import (
	"strings"
)

// Object represents the encoding of structured data within an XML node
type Object struct {
	w       writer
	scratch *[]byte

	endElement *EndElement
}

// newObject returns a new object encoder type
func newObject(w writer, scratch *[]byte, endElement *EndElement) *Object {
	return &Object{w: w, scratch: scratch, endElement: endElement}
}

// Key returns a Value encoder that should be used to encode a XML value type.
// It sets the given named key builder function into the XML object value encoder.
// Key takes a element name along with a list of attributes.
func (o *Object) Key(name string, attr *[]Attr) Value {
	var space string

	// check if name contains namespace identifier
	if strings.ContainsRune(name, ':') {
		ns := strings.SplitN(name, ":", 2)
		space = ns[0]
		name = ns[1]
	}

	// build a start element
	startElement := StartElement{
		Name: Name{
			Space: space,
			Local: name,
		},
	}

	if attr != nil {
		startElement.Attr = *attr
	}

	endElement := startElement.End()

	return newValue(o.w, o.scratch, &startElement, &endElement)
}

// Close closes an object.
func (o *Object) Close() {
	writeEndElement(o.w, o.endElement)
	o.endElement = nil
}
