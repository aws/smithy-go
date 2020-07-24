package xml

import (
	"encoding/base64"
	"fmt"
	"math"
	"math/big"
	"strconv"
)

// Value represents an XML Value type
// XML Value types: Object, Array, Map, String, Number, Boolean.
type Value struct {
	w       writer
	scratch *[]byte

	startElement StartElement

	isFlattened bool
}

// newValue returns a new Value encoder. newValue does NOT write the start element tag
func newValue(w writer, scratch *[]byte, startElement StartElement) Value {
	return Value{
		w:            w,
		scratch:      scratch,
		startElement: startElement,
	}
}

// newWrappedValue writes the start element xml tag and returns a Value
func newWrappedValue(w writer, scratch *[]byte, startElement StartElement) Value {
	writeStartElement(w, startElement)
	return Value{w: w, scratch: scratch, startElement: startElement}
}

// writeStartElement takes in a start element and writes it.
// It handles namespace, attributes in start element.
func writeStartElement(w writer, el StartElement) error {
	if el.isZero() {
		return fmt.Errorf("xml start element cannot be nil")
	}

	w.WriteRune(leftAngleBracket)

	if len(el.Name.Space) != 0 {
		w.WriteString(el.Name.Space)
		w.WriteRune(colon)
	}
	w.WriteString(el.Name.Local)

	for _, attr := range el.Attr {
		w.WriteRune(' ')
		buildAttribute(w, &attr)
	}

	w.WriteRune(rightAngleBracket)

	return nil
}

// buildAttribute writes an attribute from a provided Attribute
// For a namespace attribute, the attr.Name.Space must be defined as "xmlns".
// https://www.w3.org/TR/REC-xml-names/#NT-DefaultAttName
func buildAttribute(w writer, attr *Attr) {
	// if local, space both are not empty
	if len(attr.Name.Space) != 0 && len(attr.Name.Local) != 0 {
		w.WriteString(attr.Name.Space)
		w.WriteRune(colon)
	}

	// if prefix is empty, the default `xmlns` space should be used as prefix.
	if len(attr.Name.Local) == 0 {
		attr.Name.Local = attr.Name.Space
	}

	w.WriteString(attr.Name.Local)
	w.WriteRune(equals)
	w.WriteRune(quote)
	w.WriteString(attr.Value)
	w.WriteRune(quote)
}

// writeEndElement takes in a end element and writes it.
func writeEndElement(w writer, el EndElement) error {
	if el.isZero() {
		return fmt.Errorf("xml end element cannot be nil")
	}

	w.WriteRune(leftAngleBracket)
	w.WriteRune(forwardSlash)

	if len(el.Name.Space) != 0 {
		w.WriteString(el.Name.Space)
		w.WriteRune(colon)
	}
	w.WriteString(el.Name.Local)
	w.WriteRune(rightAngleBracket)

	return nil
}

// String encodes v as a XML string.
// It will auto close the parent xml element tag.
func (xv Value) String(v string) {
	escapeString(xv.w, v)
	xv.Close()
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
// It will auto close the parent xml element tag.
func (xv Value) Long(v int64) {
	*xv.scratch = strconv.AppendInt((*xv.scratch)[:0], v, 10)
	xv.w.Write(*xv.scratch)

	xv.Close()
}

// Float encodes v as a XML number.
// It will auto close the parent xml element tag.
func (xv Value) Float(v float32) {
	xv.float(float64(v), 32)
	xv.Close()
}

// Double encodes v as a XML number.
// It will auto close the parent xml element tag.
func (xv Value) Double(v float64) {
	xv.float(v, 64)
	xv.Close()
}

func (xv Value) float(v float64, bits int) {
	*xv.scratch = encodeFloat((*xv.scratch)[:0], v, bits)
	xv.w.Write(*xv.scratch)
}

// Boolean encodes v as a XML boolean.
// It will auto close the parent xml element tag.
func (xv Value) Boolean(v bool) {
	*xv.scratch = strconv.AppendBool((*xv.scratch)[:0], v)
	xv.w.Write(*xv.scratch)

	xv.Close()
}

// Base64EncodeBytes writes v as a base64 value in XML string.
// It will auto close the parent xml element tag.
func (xv Value) Base64EncodeBytes(v []byte) {
	encodeByteSlice(xv.w, (*xv.scratch)[:0], v)
	xv.Close()
}

// BigInteger encodes v big.Int as XML value.
// It will auto close the parent xml element tag.
func (xv Value) BigInteger(v *big.Int) {
	xv.w.Write([]byte(v.Text(10)))
	xv.Close()
}

// BigDecimal encodes v big.Float as XML value.
// It will auto close the parent xml element tag.
func (xv Value) BigDecimal(v *big.Float) {
	if i, accuracy := v.Int64(); accuracy == big.Exact {
		xv.Long(i)
		return
	}

	xv.w.Write([]byte(v.Text('e', -1)))
	xv.Close()
}

// Write writes v directly to the xml document
// if escapeXMLText is set to true, write will escape text.
// It will auto close the parent xml element tag.
func (xv Value) Write(v []byte, escapeXMLText bool) {
	// escape and write xml text
	if escapeXMLText {
		escapeText(xv.w, v)
	} else {
		// write xml directly
		xv.w.Write(v)
	}

	xv.Close()
}

// MemberElement returns a structure or simple shape member element encoding.
// It returns a Value. Member Element should be used for Nested structure or simple elements.
// A call to MemberElement will write nested element tags directly using the
// provided start element. The value returned by MemberElement should be closed.
func (xv Value) MemberElement(element StartElement) Value {
	v := newWrappedValue(xv.w, xv.scratch, element)
	v.isFlattened = xv.isFlattened
	return v
}

// CollectionElement returns a collection shape member element encoding.
// This method should be used to get Value when encoding a map or an array.
// Unlike MemberElement, CollectionElement will NOT write element tags
// directly for the associated start element.
// The Value returned by the Collection Element does not need to be closed.
func (xv Value) CollectionElement(element StartElement) Value {
	v := newValue(xv.w, xv.scratch, element)
	v.isFlattened = xv.isFlattened
	return v
}

// Array returns an array encoder. By default, the members of array are
// wrapped with `<member>` element tag.
//
// for eg,`<someList><member>entry</member><member>entry2</member></someList>`.
func (xv Value) Array() *Array {
	return newArray(xv.w, xv.scratch, arrayMemberWrapper, xv.startElement)
}

/*
ArrayWithCustomName returns an array encoder.

It takes name as an argument, the name will used to wrap xml array entries.
for eg, `<someList><customName>entry1</customName></someList>`
Here `customName` element tag will be wrapped on each array member.
*/
func (xv Value) ArrayWithCustomName(element StartElement) *Array {
	return newArray(xv.w, xv.scratch, element, xv.startElement)
}

/*
FlattenedArray returns a flattened array encoder.

FlattenedArray Encoder wraps each member with array's root element tag, thus
flattening the array.

for eg,`<someList>entry1</someList><someList>entry2</someList>`.
*/
func (xv Value) FlattenedArray() *Array {
	return newFlattenedArray(xv.w, xv.scratch, xv.startElement)
}

/*
Map returns a map encoder. By default, the map entries are
wrapped with `<entry>` element tag.

for eg. `<someMap><entry><k>entry1</k><v>value1</v></entry><entry><k>entry2</k><v>value2</v></entry></someMap>`
*/
func (xv Value) Map() *Map {
	return newMap(xv.w, xv.scratch, xv.startElement)
}

/*
FlattenedMap returns a flattened map encoder.

FlattenedMap Encoder wraps each entry with map's root element tag, thus
flattening the map.
for eg, `<someMap><key>entryKey1</key><value>entryValue1</value></someMap>`.
*/
func (xv Value) FlattenedMap() *Map {
	return newFlattenedMap(xv.w, xv.scratch, xv.startElement)
}

// Encodes a float value as per the xml stdlib xml encoder
func encodeFloat(dst []byte, v float64, bits int) []byte {
	if math.IsInf(v, 0) || math.IsNaN(v) {
		panic(fmt.Sprintf("invalid float value: %s", strconv.FormatFloat(v, 'g', -1, bits)))
	}

	// return []byte(strconv.FormatFloat(v, 'g', -1, bits))

	abs := math.Abs(v)
	fmt := byte('f')

	if abs != 0 {
		if bits == 64 && (abs < 1e-6 || abs >= 1e21) || bits == 32 && (float32(abs) < 1e-6 || float32(abs) >= 1e21) {
			fmt = 'e'
		}
	}

	dst = strconv.AppendFloat(dst, v, fmt, -1, bits)

	if fmt == 'e' {
		// clean up e-09 to e-9
		n := len(dst)
		if n >= 4 && dst[n-4] == 'e' && dst[n-3] == '-' && dst[n-2] == '0' {
			dst[n-2] = dst[n-1]
			dst = dst[:n-1]
		}
	}

	return dst
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

func (xv Value) IsFlattened() bool {
	return xv.isFlattened
}

// TODO: fix this
func (xv Value) SetFlattened() Value {
	v := xv
	v.isFlattened = true
	return v
}

// Close closes the value
func (xv Value) Close() {
	writeEndElement(xv.w, xv.startElement.End())
}
