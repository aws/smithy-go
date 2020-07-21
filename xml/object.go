package xml

import (
	"bytes"
	"encoding/xml"
	"strings"
)

// Object represents the encoding of structured data within an XML node
type Object struct {
	w       *bytes.Buffer
	scratch *[]byte

	endElement *xml.EndElement
}

// newObject returns a new object encoder type
func newObject(w *bytes.Buffer, scratch *[]byte, endElement *xml.EndElement) *Object {
	return &Object{w: w, scratch: scratch, endElement: endElement}
}

// Key returns a Value encoder that should be used to encode a XML value type.
// It sets the given named key builder function into the XML object value encoder.
// Key takes optional functional arguments to set tag metadata when building the element tag.
func (o *Object) Key(name string, attr *[]xml.Attr) Value {
	var space string
	if strings.ContainsRune(name, ':') {
		ns := strings.SplitN(name, ":", 2)
		space = ns[0]
		name = ns[1]
	}

	startElement := xml.StartElement{
		Name: xml.Name{
			Space: space,
			Local: name,
		},
		Attr: *attr,
	}

	endElement := startElement.End()

	return newValue(o.w, o.scratch, &startElement, &endElement)
}

//
// func writeOpenTag(w *bytes.Buffer, name string) {
// 	w.WriteRune(leftAngleBracket)
// 	w.Write([]byte(name))
// }
//
// func writeCloseTag(w *bytes.Buffer, name string) {
// 	w.WriteRune(leftAngleBracket)
// 	w.WriteRune(forwardSlash)
// 	w.Write([]byte(name))
// 	w.WriteRune(rightAngleBracket)
// }

func (o *Object) Close() {
	writeEndElement(o.w, o.endElement)
}

/*
TagMetadata represents the metadata required when building the
xml element tag.

Namespaces are stored as key value pairs in a map where Namespace URI is the key,
and the namespace prefix corresponds to the value. The namespace prefix can be empty,
whereas namespace URI is required if a namespace is set.

Attributes are stored as key value pairs in a map where Attribute name is the key,
and Attribute value corresponds to the value.

This is in accordance to https://awslabs.github.io/smithy/1.0/spec/core/xml-traits.html#xmlattribute-trait
*/
type TagMetadata struct {
	Namespaces map[string]string
	Attributes map[string]string
}
