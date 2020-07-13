package xml

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"math"
	"math/big"
	"strconv"
)

// Value represents an XML Value type
// XML Value types: Object, Array, Map, String, Number, Boolean, and Null.
type Value struct {
	w       *bytes.Buffer
	scratch *[]byte

	openTagWriterFn  func()
	closeTagWriterFn func()
}

// newValue returns a new Value encoder
func newValue(w *bytes.Buffer, scratch *[]byte, openTagWriterFn func(), closeTagWriterFn func()) Value {
	return Value{
		w:                w,
		scratch:          scratch,
		openTagWriterFn:  openTagWriterFn,
		closeTagWriterFn: closeTagWriterFn,
	}
}

// String encodes v as a XML string.
// It will auto close the xml element tag.
func (jv Value) String(v string) {
	jv.openTagWriterFn()
	escapeString(jv.w, v)
	jv.closeTagWriterFn()
}

// Byte encodes v as a XML number
func (jv Value) Byte(v int8) {
	jv.Long(int64(v))
}

// Short encodes v as a XML number
func (jv Value) Short(v int16) {
	jv.Long(int64(v))
}

// Integer encodes v as a XML number
func (jv Value) Integer(v int32) {
	jv.Long(int64(v))
}

// Long encodes v as a XML number.
// It will auto close the xml element tag.
func (jv Value) Long(v int64) {
	jv.openTagWriterFn()

	*jv.scratch = strconv.AppendInt((*jv.scratch)[:0], v, 10)
	jv.w.Write(*jv.scratch)

	jv.closeTagWriterFn()
}

// Float encodes v as a XML number.
// It will auto close the xml element tag.
func (jv Value) Float(v float32) {
	jv.openTagWriterFn()
	jv.float(float64(v), 32)
	jv.closeTagWriterFn()
}

// Double encodes v as a XML number.
// It will auto close the xml element tag.
func (jv Value) Double(v float64) {
	jv.openTagWriterFn()
	jv.float(v, 64)
	jv.closeTagWriterFn()
}

func (jv Value) float(v float64, bits int) {
	*jv.scratch = encodeFloat(v, bits)
	jv.w.Write(*jv.scratch)
}

// Boolean encodes v as a XML boolean.
// It will auto close the xml element tag.
func (jv Value) Boolean(v bool) {
	jv.openTagWriterFn()

	*jv.scratch = strconv.AppendBool((*jv.scratch)[:0], v)
	jv.w.Write(*jv.scratch)

	jv.closeTagWriterFn()
}

// Base64EncodeBytes writes v as a base64 value in XML string.
// It will auto close the xml element tag.
func (jv Value) Base64EncodeBytes(v []byte) {
	jv.openTagWriterFn()
	encodeByteSlice(jv.w, (*jv.scratch)[:0], v)
	jv.closeTagWriterFn()
}

// BigInteger encodes v big.Int as XML value.
// It will auto close the xml element tag.
func (jv Value) BigInteger(v *big.Int) {
	jv.openTagWriterFn()
	jv.w.Write([]byte(v.Text(10)))
	jv.closeTagWriterFn()
}

// BigDecimal encodes v big.Float as XML value.
// It will auto close the xml element tag.
func (jv Value) BigDecimal(v *big.Float) {
	if i, accuracy := v.Int64(); accuracy == big.Exact {
		jv.Long(i)
		return
	}

	jv.openTagWriterFn()
	jv.w.Write([]byte(v.Text('e', -1)))
	jv.closeTagWriterFn()
}

// Null encodes a null element tag like <root></root>.
// It will auto close the xml element tag.
func (jv Value) Null() {
	// write open tag for the parent object
	jv.openTagWriterFn()

	// close tag
	jv.closeTagWriterFn()
}

// Write writes v directly to the xml document
// if escapeXMLText is set to true, write will escape text.
// It will auto close the xml element tag.
func (jv Value) Write(v []byte, escapeXMLText bool) {
	jv.openTagWriterFn()

	// escape and write xml text
	if escapeXMLText {
		escapeText(jv.w, v)
	} else {
		// write xml directly
		jv.w.Write(v)
	}

	jv.closeTagWriterFn()
}

// RootElement builds a root element encoding
func (jv Value) RootElement() (o *Object) {
	return newObject(jv.w, jv.scratch)
}

// NestedElement returns a nested element encoding.
// It returns a point to element object and a close function that will
// close the element tag.
func (jv Value) NestedElement() (o *Object, closeFn func()) {
	// write open tag for the parent object
	jv.openTagWriterFn()

	return newObject(jv.w, jv.scratch), jv.closeTagWriterFn
}

// Array returns an array encoder and a close function that will close
// the array's root element tag. By default, the members of array are
// wrapped with `<member>` element tag.
func (jv Value) Array() (a *Array, closeFn func()) {
	// write open tag for the parent element
	jv.openTagWriterFn()

	return newArray(jv.w, jv.scratch), jv.closeTagWriterFn
}

// ArrayWithCustomName returns an array encoder and a close function that will
// close the array's root element tag.
//
// It takes name as an argument, the name will used to wrap xml array entries.
// for eg, <someList><customName>entry1</customName><someList>
// Here `customName` element tag will be wrapped on each array member.
func (jv Value) ArrayWithCustomName(name string) (a *Array, closeFn func()) {
	// write open tag for the parent element
	jv.openTagWriterFn()

	return newArrayWithCustomName(jv.w, jv.scratch, name), jv.closeTagWriterFn
}

// FlattenedArray returns a flattened array encoder. Unlike other array encoders
// it DOES NOT return an close function.
//
// FlattenedArray Encoder wraps each member with array's root element tag, thus
// flattening the array.
//
// for eg,`<someList>entry1</someList><someList>entry2</someList>`.
func (jv Value) FlattenedArray() (a *Array) {
	return newFlattenedArray(jv.w, jv.scratch, jv.openTagWriterFn, jv.closeTagWriterFn)
}

// Map returns a map encoder and a close function that will close
// the map's root element tag. By default, the map entries are
// wrapped with `<entry>` element tag.
func (jv Value) Map() (m *Map, closeFn func()) {
	jv.openTagWriterFn()
	return newMap(jv.w, jv.scratch), jv.closeTagWriterFn
}

// FlattenedMap returns a flattened map encoder. Unlike other map encoder
// it DOES NOT return an close function.
//
// FlattenedMap Encoder wraps each entry with map's root element tag, thus
// flattening the map.
// for eg, `<someMap><key>entryKey1</key><value>entryValue1</value>`.
func (jv Value) FlattenedMap() *Map {
	return newFlattenedMap(jv.w, jv.scratch, jv.openTagWriterFn, jv.closeTagWriterFn)
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
func encodeByteSlice(w *bytes.Buffer, scratch []byte, v []byte) {
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
