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

// Value describes a CBOR data item.
//
// The following types implement Value:
//   - Uint
//   - NegInt
//   - Slice
//   - String
//   - List
//   - Map
//   - Tag
//   - Bool
//   - Nil
//   - Undefined
//   - Float32
//   - Float64
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
	_ Value = Bool(false)
	_ Value = (*Nil)(nil)
	_ Value = (*Undefined)(nil)
	_ Value = Float32(0)
	_ Value = Float64(0)
)

// Uint describes a CBOR uint (major type 0).
type Uint uint64

// NegInt describes a CBOR negative int (major type 1).
type NegInt uint64

// Slice describes a CBOR byte slice (major type 2).
type Slice []byte

// String describes a CBOR text string (major type 3).
type String string

// List describes a CBOR list (major type 4).
type List []Value

// Map describes a CBOR map (major type 5).
//
// The type signature of the map's key is restricted to string as it is in
// Smithy.
type Map map[string]Value

// Tag describes a CBOR-tagged value (major type 6).
type Tag struct {
	ID    uint64
	Value Value
}

// Bool describes a boolean value (major type 7, argument 20/21).
type Bool bool

// Nil is the `nil` / `null` literal (major type 7, argument 22).
type Nil struct{}

// Undefined is the `undefined` literal (major type 7, argument 23).
type Undefined struct{}

// Float32 describes an IEEE 754 single-precision floating-point number
// (major type 7, argument 26).
//
// Go does not natively support float16, all values encoded as such (major type
// 7, argument 25) must be represented by this variant instead.
type Float32 float32

// Float64 describes an IEEE 754 double-precision floating-point number
// (major type 7, argument 27).
type Float64 float64

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
