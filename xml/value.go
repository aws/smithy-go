package xml

import (
	"bytes"
	"fmt"
	"math"
	"math/big"
	"strconv"
)

// TODO: Change documentation to relate to XML encoding

// Value represents an XML Value type
// XML Value types: Object, Array, String, Number, Boolean, and Null
type Value struct {
	w       *bytes.Buffer
	scratch *[]byte
	key     string
}

// newValue returns a new Value encoder
func newValue(w *bytes.Buffer, scratch *[]byte) Value {
	v := Value{w: w, scratch: scratch}
	return v
}

func newValueWithKey(w *bytes.Buffer, scratch *[]byte, key string) Value {
	return Value{w: w, scratch: scratch, key: key}
}

// String encodes v as a JSON string
func (jv Value) String(v string) {
	// TODO: escape some characters
	jv.w.Write([]byte(v))
	closeKeyTag(jv.w, &jv.key)
}

// Byte encodes v as a JSON number
func (jv Value) Byte(v int8) {
	jv.Long(int64(v))
}

// Short encodes v as a JSON number
func (jv Value) Short(v int16) {
	jv.Long(int64(v))
}

// Integer encodes v as a JSON number
func (jv Value) Integer(v int32) {
	jv.Long(int64(v))
	closeKeyTag(jv.w, &jv.key)
}

// Long encodes v as a JSON number
func (jv Value) Long(v int64) {
	*jv.scratch = strconv.AppendInt((*jv.scratch)[:0], v, 10)
	jv.w.Write(*jv.scratch)
}

// Float encodes v as a JSON number
func (jv Value) Float(v float32) {
	jv.float(float64(v), 32)
	closeKeyTag(jv.w, &jv.key)
}

// Double encodes v as a JSON number
func (jv Value) Double(v float64) {
	jv.float(v, 64)
}

func (jv Value) float(v float64, bits int) {
	*jv.scratch = encodeFloat((*jv.scratch)[:0], v, bits)
	jv.w.Write(*jv.scratch)
}

// Boolean encodes v as a JSON boolean
func (jv Value) Boolean(v bool) {
	*jv.scratch = strconv.AppendBool((*jv.scratch)[:0], v)
	jv.w.Write(*jv.scratch)
}

// Base64EncodeBytes writes v as a base64 value in JSON string
func (jv Value) Base64EncodeBytes(v []byte) {
	// encodeByteSlice(jv.w, (*jv.scratch)[:0], v)
}

// Write writes v directly to the JSON document
func (jv Value) Write(v []byte) {
	jv.w.Write(v)
}

// Object returns a new Object encoder
func (jv Value) Object() *Object {
	return newObject(jv.w, jv.scratch)
}

// func (jv Value) NestedObject() *Object {
// 	return newObjectWithKey(jv.w, jv.scratch, jv.key)
// }

func (jv Value) Close() {
	closeKeyTag(jv.w, &jv.key)
}

// TODO: fix naming .. may be ArrayEncoder?
// Array returns an array encoder
func (jv Value) Array() *Array {
	return newArray(jv.w, jv.scratch)
}

func (jv Value) ArrayWithKey(name string) *Array {
	return newArrayWithKey(jv.w, jv.scratch, name)
}

// FlattenedArray returns a flattened array encoder
func (jv Value) FlattenedArray() *Array {
	return newArrayWithKey(jv.w, jv.scratch, jv.key)
}

// Map returns a map encoder
func (jv Value) Map() *Map {
	return newMap(jv.w, jv.scratch)
}

// FlattenedMap returns a flattened map encoder
func (jv Value) FlattenedMap() *Map {
	return newFlattenedMap(jv.w, jv.scratch, jv.key)
}

// BigInteger encodes v as JSON value
func (jv Value) BigInteger(v *big.Int) {
	jv.w.Write([]byte(v.Text(10)))
}

// BigDecimal encodes v as JSON value
func (jv Value) BigDecimal(v *big.Float) {
	if i, accuracy := v.Int64(); accuracy == big.Exact {
		jv.Long(i)
		return
	}
	// TODO: Should this try to match ES6 ToString similar to stdlib JSON?
	jv.w.Write([]byte(v.Text('e', -1)))
}

// Encodes a float value into dst while attempting to conform to ES6 ToString for Numbers
//
// Based on encoding/json floatEncoder from the Go Standard Library
// https://golang.org/src/encoding/json/encode.go
func encodeFloat(dst []byte, v float64, bits int) []byte {
	if math.IsInf(v, 0) || math.IsNaN(v) {
		panic(fmt.Sprintf("invalid float value: %s", strconv.FormatFloat(v, 'g', -1, bits)))
	}

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

// Based on encoding/json encodeByteSlice from the Go Standard Library
// https://golang.org/src/encoding/json/encode.go
// func encodeByteSlice(w *bytes.Buffer, scratch []byte, v []byte) {
// 	if v == nil {
// 		w.WriteString(null)
// 		return
// 	}
//
// 	w.WriteRune(quote)
//
// 	encodedLen := base64.StdEncoding.EncodedLen(len(v))
// 	if encodedLen <= len(scratch) {
// 		// If the encoded bytes fit in e.scratch, avoid an extra
// 		// allocation and use the cheaper Encoding.Encode.
// 		dst := scratch[:encodedLen]
// 		base64.StdEncoding.Encode(dst, v)
// 		w.Write(dst)
// 	} else if encodedLen <= 1024 {
// 		// The encoded bytes are short enough to allocate for, and
// 		// Encoding.Encode is still cheaper.
// 		dst := make([]byte, encodedLen)
// 		base64.StdEncoding.Encode(dst, v)
// 		w.Write(dst)
// 	} else {
// 		// The encoded bytes are too long to cheaply allocate, and
// 		// Encoding.Encode is no longer noticeably cheaper.
// 		enc := base64.NewEncoder(base64.StdEncoding, w)
// 		enc.Write(v)
// 		enc.Close()
// 	}
//
// 	w.WriteRune(quote)
// }
