package json

import (
	"bytes"
	"math/big"
	"time"

	"github.com/aws/smithy-go"
)

// Encoder is JSON encoder that supports construction of JSON values
// using methods.
type Encoder struct {
	w *bytes.Buffer
	Value
}

// NewEncoder returns a new JSON encoder
func NewEncoder() *Encoder {
	writer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	return &Encoder{w: writer, Value: newValue(writer, &scratch)}
}

// String returns the String output of the JSON encoder
func (e Encoder) String() string {
	return e.w.String()
}

// Bytes returns the []byte slice of the JSON encoder
func (e Encoder) Bytes() []byte {
	return e.w.Bytes()
}

type stack struct {
	values []any
}

type empty struct{}

func (s *stack) Top() any {
	if len(s.values) == 0 {
		return empty{}
	}
	return s.values[len(s.values)-1]
}

func (s *stack) Push(v any) {
	s.values = append(s.values, v)
}

func (s *stack) Pop() {
	s.values = s.values[:len(s.values)-1]
}

// ShapeSerializer implements marshaling of Smithy shapes to JSON.
type ShapeSerializer struct {
	root *Encoder
	head stack
}

var _ smithy.ShapeSerializer = (*ShapeSerializer)(nil)

func (ss *ShapeSerializer) Bytes() []byte {
	return ss.root.Bytes()
}

func (ss *ShapeSerializer) WriteBool(s *smithy.Schema, v bool) {
	switch enc := ss.head.Top().(type) {
	case *Object:
		enc.Key(s.MemberName()).Boolean(v)
	case *Array:
		enc.Value().Boolean(v)
	default:
		ss.root.Boolean(v)
	}
}

func (ss *ShapeSerializer) WriteInt8(s *smithy.Schema, v int8) {
	switch enc := ss.head.Top().(type) {
	case *Object:
		enc.Key(s.MemberName()).Byte(v)
	case *Array:
		enc.Value().Byte(v)
	default:
		ss.root.Byte(v)
	}
}

func (ss *ShapeSerializer) WriteInt16(s *smithy.Schema, v int16) {
	switch enc := ss.head.Top().(type) {
	case *Object:
		enc.Key(s.MemberName()).Short(v)
	case *Array:
		enc.Value().Short(v)
	default:
		ss.root.Short(v)
	}
}

func (ss *ShapeSerializer) WriteInt32(s *smithy.Schema, v int32) {
	switch enc := ss.head.Top().(type) {
	case *Object:
		enc.Key(s.MemberName()).Integer(v)
	case *Array:
		enc.Value().Integer(v)
	default:
		ss.root.Integer(v)
	}
}

func (ss *ShapeSerializer) WriteInt64(s *smithy.Schema, v int64) {
	switch enc := ss.head.Top().(type) {
	case *Object:
		enc.Key(s.MemberName()).Long(v)
	case *Array:
		enc.Value().Long(v)
	default:
		ss.root.Long(v)
	}
}

func (ss *ShapeSerializer) WriteString(s *smithy.Schema, v string) {
	switch enc := ss.head.Top().(type) {
	case *Object:
		enc.Key(s.MemberName()).String(v)
	case *Array:
		enc.Value().String(v)
	default:
		ss.root.Value.String(v)
	}
}

func (ss *ShapeSerializer) WriteBlob(s *smithy.Schema, v []byte) {
	switch enc := ss.head.Top().(type) {
	case *Object:
		enc.Key(s.MemberName()).Base64EncodeBytes(v)
	case *Array:
		enc.Value().Base64EncodeBytes(v)
	default:
		ss.root.Value.Base64EncodeBytes(v)
	}
}

func (ss *ShapeSerializer) WriteList(s *smithy.Schema) func() {
	switch enc := ss.head.Top().(type) {
	case *Object:
		ss.head.Push(enc.Key(s.MemberName()).Array())
	case *Array:
		ss.head.Push(enc.Value().Array())
	default:
		ss.head.Push(ss.root.Array())
	}
	return ss.head.Pop
}

func (ss *ShapeSerializer) WriteMap(s *smithy.Schema) func() {
	switch enc := ss.head.Top().(type) {
	case *Object:
		ss.head.Push(enc.Key(s.MemberName()).Object())
	case *Array:
		ss.head.Push(enc.Value().Object())
	default:
		ss.head.Push(ss.root.Object())
	}
	return ss.head.Pop
}

func (ss *ShapeSerializer) WriteFloat32(s *smithy.Schema, v float32) {
	panic("TODO")
}

func (ss *ShapeSerializer) WriteFloat64(s *smithy.Schema, v float64) {
	panic("TODO")
}

func (ss *ShapeSerializer) WriteTime(s *smithy.Schema, v time.Time) {
	panic("TODO")
}

func (ss *ShapeSerializer) WriteDocument(s *smithy.Schema, v any) {
	panic("TODO")
}

func (ss *ShapeSerializer) WriteNil(s *smithy.Schema) {
	panic("TODO")
}

func (ss *ShapeSerializer) WriteBigInteger(s *smithy.Schema, v big.Int) {
	panic("unimplemented")
}

func (ss *ShapeSerializer) WriteBigDecimal(s *smithy.Schema, v big.Float) {
	panic("unimplemented")
}
