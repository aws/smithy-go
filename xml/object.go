package xml

import (
	"bytes"
)

// Object represents the encoding of a XML Object type
type Object struct {
	w       *bytes.Buffer
	scratch *[]byte
}

// newObject returns a new object encoder type
func newObject(w *bytes.Buffer, scratch *[]byte) *Object {
	return &Object{w: w, scratch: scratch}
}

// Key returns a Value encoder that should be used to encode a XML value type.
// It sets the given named key builder function into the XML object value encoder.
// Key takes optional functional arguments to set tag metadata when building the element tag.
func (o *Object) Key(name string, opts ...func(t *TagMetadata)) Value {
	// openTagFn is a element start tag builder function
	var openTagFn = func() {
		writeOpenTag(o.w, name)
		defer o.w.WriteRune(rightAngleBracket)
		for _, fn := range opts {
			var m TagMetadata
			fn(&m)

			// set the namespace URI and prefix in element tag
			if len(m.NamespacePrefix) != 0 && len(m.NamespaceURI) != 0 {
				o.w.Write([]byte(" xmlns:" + m.NamespacePrefix + "=\"" + m.NamespaceURI + "\""))
			}

			if len(m.AttributeValue) != 0 {
				if len(m.AttributeName) != 0 {
					o.w.Write([]byte(" " + m.AttributeName + "=\"" + m.AttributeValue + "\""))
				} else {
					// attr is the default attribute name
					o.w.Write([]byte(" attr" + "=\"" + m.AttributeValue + "\""))
				}
			}
		}
	}

	var closeTagFn = func() {
		writeCloseTag(o.w, name)
	}

	return newValue(o.w, o.scratch, openTagFn, closeTagFn)
}

func writeOpenTag(w *bytes.Buffer, name string) {
	w.WriteRune(leftAngleBracket)
	w.Write([]byte(name))
}

func writeCloseTag(w *bytes.Buffer, name string) {
	w.WriteRune(leftAngleBracket)
	w.WriteRune(forwardSlash)
	w.Write([]byte(name))
	w.WriteRune(rightAngleBracket)
}

// TagMetadata represents the metadata required when building the
// xml element tag. You should use it to set Namespace URI and prefix,
// Attribute name and value.
type TagMetadata struct {
	NamespacePrefix string
	NamespaceURI    string
	AttributeName   string
	AttributeValue  string
}
