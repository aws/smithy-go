package xml

import (
	"bytes"
)

// TODO: explore usage of scratch. Can it be used to transmit key?
// Object represents the encoding of a XML Object type
type Object struct {
	w       *bytes.Buffer
	key     string
	scratch *[]byte
}

func newObject(w *bytes.Buffer, scratch *[]byte) *Object {
	return &Object{w: w, scratch: scratch}
}

func newObjectWithKey(w *bytes.Buffer, scratch *[]byte, key string) *Object {
	return &Object{w: w, scratch: scratch, key: key}
}

func (o *Object) writeKey(key string) {
	writeKeyTag(o.w, key)
}

// Key adds the given named key to the XML object.
// Returns a Value encoder that should be used to encode
// a XML value type.
func (o *Object) Key(name string, opts ...func() TagMetadata) Value {
	o.writeKey(name)
	defer o.w.WriteRune(rightAngleBracket)

	for _, fn := range opts {
		m := fn()
		// set the name space in element tag
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

	return newValueWithKey(o.w, o.scratch, name)
}

func (o *Object) SetKey(name string) Value {
	o.key = name
	return newValueWithKey(o.w, o.scratch, o.key)
}

// func (o *Object) Close() {
// 	closeKeyTag(o.w, &o.key)
// }

// TODO: move these to better place?
func writeKeyTag(w *bytes.Buffer, key string) {
	w.WriteRune(leftAngleBracket)
	w.Write([]byte(key))
}

func closeKeyTag(w *bytes.Buffer, key *string) {
	if key == nil {
		return
	}

	w.WriteRune(leftAngleBracket)
	w.WriteRune(forwardSlash)
	w.Write([]byte(*key))
	w.WriteRune(rightAngleBracket)
}

type TagMetadata struct {
	NamespacePrefix string
	NamespaceURI    string
	AttributeName   string
	AttributeValue  string
}


// TODO: Refactor Object to have close instead of Value
