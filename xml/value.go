package xml

import (
	"encoding/base64"
	"fmt"
	"math"
	"math/big"
	"strconv"
	"strings"
)

// Value represents an XML Value type
// XML Value types: Object, Array, Map, String, Number, Boolean, and Null.
type Value struct {
	w       writer
	scratch *[]byte

	startElement *StartElement
	endElement   *EndElement
}

// newValue returns a new Value encoder
func newValue(w writer, scratch *[]byte, startElement *StartElement, endElement *EndElement) Value {
	return Value{
		w:            w,
		scratch:      scratch,
		startElement: startElement,
		endElement:   endElement,
	}
}

// writeStartElement takes in a start element and writes it.
// It handles namespace, attributes in start element.
func writeStartElement(w writer, el *StartElement) error {
	if el == nil {
		return nil
	}

	w.WriteRune(leftAngleBracket)

	if len(el.Name.Space) != 0 {
		w.WriteString(el.Name.Space)
		w.WriteRune(colon)
	}
	w.WriteString(el.Name.Local)

	for _, attr := range el.Attr {
		// if attribute name len is zero or name.space is `xmlns`, it is a namespace attribute
		if len(attr.Name.Local) == 0 || strings.EqualFold(attr.Name.Space, "xmlns") {
			w.WriteRune(' ')
			buildNamespace(w, &attr)
		} else {
			w.WriteRune(' ')
			buildAttribute(w, &attr)
		}
	}

	w.WriteRune(rightAngleBracket)

	return nil
}

// buildNamespace writes the namespace from a provided Attribute type
func buildNamespace(w writer, attr *Attr) {
	if len(attr.Name.Space) != 0 {
		w.WriteString(attr.Name.Space)
	} else {
		// xmlns is the default space of a namespace.
		// if no prefix is provided xmlns is used.
		// for eg: `@xmlNamespace(uri: "http://foo.com")` is
		// represented as `xmlns="http://example.com"`
		//
		// https://awslabs.github.io/smithy/1.0/spec/core/xml-traits.html#xmlnamespace-trait
		w.WriteString("xmlns")
	}

	if len(attr.Name.Local) != 0 {
		w.WriteRune(colon)
		w.WriteString(attr.Name.Local)
	}

	w.WriteRune(equals)
	w.WriteRune(quote)
	w.WriteString(attr.Value)
	w.WriteRune(quote)
}

// buildAttribute writes an attribute from a provided Attribute
func buildAttribute(w writer, attr *Attr) {
	w.WriteString(attr.Name.Local)
	w.WriteRune(equals)
	w.WriteRune(quote)
	w.WriteString(attr.Value)
	w.WriteRune(quote)
}

// writeEndElement takes in a end element and writes it.
func writeEndElement(w writer, el *EndElement) error {
	// If end element is nil
	if el == nil {
		return nil
	}

	w.WriteRune(leftAngleBracket)
	w.WriteRune(forwardSlash)

	if len(el.Name.Space) != 0 {
		w.WriteString(el.Name.Space + ":")
	}
	w.WriteString(el.Name.Local)
	w.WriteRune(rightAngleBracket)

	return nil
}

// String encodes v as a XML string.
// It will auto close the xml element tag.
func (xv Value) String(v string) {
	writeStartElement(xv.w, xv.startElement)
	escapeString(xv.w, v)
	writeEndElement(xv.w, xv.endElement)
}

// Byte encodes v as a XML number
func (xv Value) Byte(v int8) {
	xv.Long(int64(v))
}

// Short encodes v as a XML number
func (xv Value) Short(v int16) {
	xv.Long(int64(v))
}

// Integer encodes v as a XML number
func (xv Value) Integer(v int32) {
	xv.Long(int64(v))
}

// Long encodes v as a XML number.
// It will auto close the xml element tag.
func (xv Value) Long(v int64) {
	writeStartElement(xv.w, xv.startElement)

	*xv.scratch = strconv.AppendInt((*xv.scratch)[:0], v, 10)
	xv.w.Write(*xv.scratch)

	writeEndElement(xv.w, xv.endElement)
}

// Float encodes v as a XML number.
// It will auto close the xml element tag.
func (xv Value) Float(v float32) {
	writeStartElement(xv.w, xv.startElement)
	xv.float(float64(v), 32)
	writeEndElement(xv.w, xv.endElement)
}

// Double encodes v as a XML number.
// It will auto close the xml element tag.
func (xv Value) Double(v float64) {
	writeStartElement(xv.w, xv.startElement)
	xv.float(v, 64)
	writeEndElement(xv.w, xv.endElement)
}

func (xv Value) float(v float64, bits int) {
	*xv.scratch = encodeFloat(v, bits)
	xv.w.Write(*xv.scratch)
}

// Boolean encodes v as a XML boolean.
// It will auto close the xml element tag.
func (xv Value) Boolean(v bool) {
	writeStartElement(xv.w, xv.startElement)
	*xv.scratch = strconv.AppendBool((*xv.scratch)[:0], v)
	xv.w.Write(*xv.scratch)
	writeEndElement(xv.w, xv.endElement)
}

// Base64EncodeBytes writes v as a base64 value in XML string.
// It will auto close the xml element tag.
func (xv Value) Base64EncodeBytes(v []byte) {
	writeStartElement(xv.w, xv.startElement)
	encodeByteSlice(xv.w, (*xv.scratch)[:0], v)
	writeEndElement(xv.w, xv.endElement)
}

// BigInteger encodes v big.Int as XML value.
// It will auto close the xml element tag.
func (xv Value) BigInteger(v *big.Int) {
	writeStartElement(xv.w, xv.startElement)
	xv.w.Write([]byte(v.Text(10)))
	writeEndElement(xv.w, xv.endElement)
}

// BigDecimal encodes v big.Float as XML value.
// It will auto close the xml element tag.
func (xv Value) BigDecimal(v *big.Float) {
	if i, accuracy := v.Int64(); accuracy == big.Exact {
		xv.Long(i)
		return
	}

	writeStartElement(xv.w, xv.startElement)
	xv.w.Write([]byte(v.Text('e', -1)))
	writeEndElement(xv.w, xv.endElement)
}

// Null encodes a null element tag like <root></root>.
// It will auto close the xml element tag.
func (xv Value) Null() {
	// write open tag for the parent object
	writeStartElement(xv.w, xv.startElement)

	// close tag
	writeEndElement(xv.w, xv.endElement)
}

// Write writes v directly to the xml document
// if escapeXMLText is set to true, write will escape text.
// It will auto close the xml element tag.
func (xv Value) Write(v []byte, escapeXMLText bool) {
	writeStartElement(xv.w, xv.startElement)

	// escape and write xml text
	if escapeXMLText {
		escapeText(xv.w, v)
	} else {
		// write xml directly
		xv.w.Write(v)
	}

	writeEndElement(xv.w, xv.endElement)
}

// NestedElement returns a nested element encoding.
// It returns a point to element object and a close function that will
// close the element tag.
func (xv Value) NestedElement() (o *Object) {
	// write open tag for the parent object
	writeStartElement(xv.w, xv.startElement)

	return newObject(xv.w, xv.scratch, xv.endElement)
}

// Array returns an array encoder. By default, the members of array are
// wrapped with `<member>` element tag.
func (xv Value) Array() *Array {
	// write start element for the array element
	writeStartElement(xv.w, xv.startElement)

	return newArray(xv.w, xv.scratch, xv.endElement, arrayMemberWrapper)
}

/*
ArrayWithCustomName returns an array encoder.

It takes name as an argument, the name will used to wrap xml array entries.
for eg, <someList><customName>entry1</customName></someList>
Here `customName` element tag will be wrapped on each array member.
*/
func (xv Value) ArrayWithCustomName(name string) (a *Array) {
	// write open tag for the array element
	writeStartElement(xv.w, xv.startElement)

	return newArray(xv.w, xv.scratch, xv.endElement, name)
}

/*
FlattenedArray returns a flattened array encoder.

FlattenedArray Encoder wraps each member with array's root element tag, thus
flattening the array.

for eg,`<someList>entry1</someList><someList>entry2</someList>`.
*/
func (xv Value) FlattenedArray() (a *Array) {
	return newFlattenedArray(xv.w, xv.scratch, xv.startElement, xv.endElement)
}

/*
Map returns a map encoder. By default, the map entries are
wrapped with `<entry>` element tag.

for eg. <entry><k>entry1</k><v>value1</v></entry><entry><k>entry2</k><v>value2</v></entry>
*/
func (xv Value) Map() *Map {
	writeStartElement(xv.w, xv.startElement)
	return newMap(xv.w, xv.scratch, xv.endElement)
}

/*
FlattenedMap returns a flattened map encoder.

FlattenedMap Encoder wraps each entry with map's root element tag, thus
flattening the map.
for eg, `<someMap><key>entryKey1</key><value>entryValue1</value></someMap>`.
*/
func (xv Value) FlattenedMap() *Map {
	return newFlattenedMap(xv.w, xv.scratch, xv.startElement, xv.endElement)
}

// Encodes a float value as per the xml stdlib xml encoder
func encodeFloat(v float64, bits int) []byte {
	if math.IsInf(v, 0) || math.IsNaN(v) {
		panic(fmt.Sprintf("invalid float value: %s", strconv.FormatFloat(v, 'g', -1, bits)))
	}

	return []byte(strconv.FormatFloat(v, 'g', -1, bits))
}

// encodeByteSlice is modified copy of json encoder's encodeByteSlice.
// It is used to base64 encode a byte slice.
func encodeByteSlice(w writer, scratch []byte, v []byte) {
	if v == nil {
		return
	}

	encodedLen := base64.StdEncoding.EncodedLen(len(v))
	if encodedLen <= len(scratch) {
		// If the encoded bytes fit in e.scratch, avoid an extra
		// allocation and use the cheaper Encoding.Encode.
		dst := scratch[:encodedLen]
		base64.StdEncoding.Encode(dst, v)
		w.Write(dst)
	} else if encodedLen <= 1024 {
		// The encoded bytes are short enough to allocate for, and
		// Encoding.Encode is still cheaper.
		dst := make([]byte, encodedLen)
		base64.StdEncoding.Encode(dst, v)
		w.Write(dst)
	} else {
		// The encoded bytes are too long to cheaply allocate, and
		// Encoding.Encode is no longer noticeably cheaper.
		enc := base64.NewEncoder(base64.StdEncoding, w)
		enc.Write(v)
		enc.Close()
	}
}
