// Package cbor implements partial encoding/decoding of concise binary object
// representation (CBOR) described in RFC 8949.
//
// This package implements a subset of the specification required to support
// the Smithy RPCv2-CBOR protocol and is NOT suitable for general application
// use.
//
// As the encoding API operates strictly off of a constructed syntax tree, the
// length of each data item in a Value will always be known and the encoder
// will always generate definite-length encodings of container types (byte/text
// string, list, map).
//
// Conversely, the decoding API will handle both definite and indefinite
// variations of encoded containers.
package cbor

import (
	"encoding/binary"
	"math"
)

// MajorType enumerates CBOR major types.
type MajorType byte

// Enumeration of CBOR major types
const (
	MajorTypeUint MajorType = iota
	MajorTypeNegInt
	MajorTypeSlice
	MajorTypeString
	MajorTypeList
	MajorTypeMap
	MajorTypeTag
	MajorType7
)

// Encode returns a byte slice that encodes the given Value.
func Encode(v Value) []byte {
	p := make([]byte, v.len())
	v.encode(p)
	return p
}

// Decode returns the Value encoded in the given byte slice.
func Decode(p []byte) (Value, error) {
	v, _, err := decode(p)
	if err != nil {
		return nil, err
	}
	return v, nil
}

// Value describes a CBOR data item.
//
// The following structures implement Value:
//   - Uint
//   - NegInt
//   - Slice
//   - String
//   - List
//   - Map
//   - Tag
//   - Major7Bool
//   - Major7Nil
//   - Major7Undefined
//   - Major7Float32
//   - Major7Float64
type Value interface {
	len() int
	encode(p []byte) int
}

var (
	_ Value = Uint(0)
	_ Value = NegInt(0)
	_ Value = Slice(nil)
	_ Value = String("")
	_ Value = List(nil)
	_ Value = Map(nil)
	_ Value = (*Tag)(nil)
	_ Value = Major7Bool(false)
	_ Value = (*Major7Nil)(nil)
	_ Value = (*Major7Undefined)(nil)
	_ Value = Major7Float32(0)
	_ Value = Major7Float64(0)
)

// UInt describes a CBOR uint (major type 0).
type Uint uint64

func (i Uint) len() int {
	return getLen(int(i))
}

func (i Uint) encode(p []byte) int {
	return encodeLen(MajorTypeUint, int(i), p)
}

// NegInt describes a CBOR negative int (major type 1).
type NegInt uint64

func (i NegInt) len() int {
	return getLen(int(int(i) - 1))
}

func (i NegInt) encode(p []byte) int {
	return encodeLen(MajorTypeNegInt, int(int(i)-1), p)
}

// Slice describes a CBOR byte slice (major type 2).
type Slice []byte

func (s Slice) len() int {
	return getLen(len(s)) + len(s)
}

func (s Slice) encode(p []byte) int {
	off := encodeLen(MajorTypeSlice, len(s), p)
	copy(p[off:], []byte(s))
	return off + len(s)
}

// String describes a CBOR text string (major type 3).
type String string

func (s String) len() int {
	return getLen(len(s)) + len(s)
}

func (s String) encode(p []byte) int {
	off := encodeLen(MajorTypeString, len(s), p)
	copy(p[off:], []byte(s))
	return off + len(s)
}

// List describes a CBOR list (major type 4).
type List []Value

func (l List) len() int {
	total := getLen(len(l))
	for _, v := range l {
		total += v.len()
	}
	return total
}

func (l List) encode(p []byte) int {
	off := encodeLen(MajorTypeList, len(l), p)
	for _, v := range l {
		off += v.encode(p[off:])
	}
	return off
}

// Map describes a CBOR map (major type 5).
//
// The type signature of the map's key is restricted to string as it is in
// Smithy.
type Map map[string]Value

func (m Map) len() int {
	total := getLen(len(m))
	for k, v := range m {
		total += String(k).len() + v.len()
	}
	return total
}

func (m Map) encode(p []byte) int {
	off := encodeLen(MajorTypeMap, len(m), p)
	for k, v := range m {
		off += String(k).encode(p[off:])
		off += v.encode(p[off:])
	}
	return off
}

// Tag describes a CBOR-tagged value (major type 6).
type Tag struct {
	ID    uint64
	Value Value
}

func (t Tag) len() int {
	return getLen(int(t.ID)) + t.Value.len()
}

func (t Tag) encode(p []byte) int {
	off := encodeLen(MajorTypeTag, int(t.ID), p)
	return off + t.Value.encode(p[off:])
}

// Major7Byte describes a boolean value (major type 7, argument 20/21).
type Major7Bool bool

func (b Major7Bool) len() int {
	return 1
}

func (b Major7Bool) encode(p []byte) int {
	if b {
		p[0] = compose(MajorType7, major7True)
	} else {
		p[0] = compose(MajorType7, major7False)
	}
	return 1
}

// Major7Nil is the `nil` / `null` literal (major type 7, argument 22).
type Major7Nil struct{}

func (*Major7Nil) len() int {
	return 1
}

func (*Major7Nil) encode(p []byte) int {
	p[0] = 0b_111_10110
	return 1
}

// Major7Undefined is the `undefined` literal (major type 7, argument 23).
type Major7Undefined struct{}

func (*Major7Undefined) len() int {
	return 1
}

func (*Major7Undefined) encode(p []byte) int {
	p[0] = 0b_111_10111
	return 1
}

// Major7Float32 describes an IEEE 754 single-precision floating-point number
// (major type 7, argument 26).
//
// Go does not natively support float16, all values encoded as such (major type
// 7, argument 25) must be represented by this variant instead.
type Major7Float32 float32

func (f Major7Float32) len() int {
	return 5
}

func (f Major7Float32) encode(p []byte) int {
	p[0] = 0b_111_11010
	binary.BigEndian.PutUint32(p[1:], math.Float32bits(float32(f)))
	return 5
}

// Major7Float64 describes an IEEE 754 double-precision floating-point number
// (major type 7, argument 27).
type Major7Float64 float64

func (f Major7Float64) len() int {
	return 9
}

func (f Major7Float64) encode(p []byte) int {
	p[0] = 0b_111_11011
	binary.BigEndian.PutUint64(p[1:], math.Float64bits(float64(f)))
	return 5
}

func encodeLen(t MajorType, ln int, p []byte) int {
	if ln < 24 {
		p[0] = byte(t)<<5 | byte(ln)
		return 1 // type and len in single byte
	} else if ln < 0x1_00 {
		p[0] = byte(t)<<5 | 24
		p[1] = byte(ln)
		return 2 // type + 1-byte len
	} else if ln < 0x1_00_00 {
		p[0] = byte(t)<<5 | 25
		binary.BigEndian.PutUint16(p[1:], uint16(ln))
		return 3 // type + 2-byte len
	} else if ln < 0x1_00_00_00_00 {
		p[0] = byte(t)<<5 | 26
		binary.BigEndian.PutUint32(p[1:], uint32(ln))
		return 5 // type + 4-byte len
	}

	p[0] = byte(t)<<5 | 27
	binary.BigEndian.PutUint64(p[1:], uint64(ln))
	return 9 // type + 8-byte len
}

func getLen(ln int) int {
	if ln < 24 {
		return 1 // type and len in single byte
	} else if ln < 0x1_00 {
		return 2 // type + 1-byte len
	} else if ln < 0x1_00_00 {
		return 3 // type + 2-byte len
	} else if ln < 0x1_00_00_00_00 {
		return 5 // type + 4-byte len
	}
	return 9 // type + 8-byte len
}

func compose(major MajorType, minor byte) byte {
	return byte(major) << 5 & minor
}
