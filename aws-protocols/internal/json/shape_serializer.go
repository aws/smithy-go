package json

import (
	"fmt"
	"math/big"
	"time"

	"github.com/aws/smithy-go"
	smithydocumentjson "github.com/aws/smithy-go/document/json"
	smithyjson "github.com/aws/smithy-go/encoding/json"
)

// ShapeSerializer implements marshaling of Smithy shapes to JSON.
type ShapeSerializer struct {
	root *smithyjson.Encoder
	head stack

	opts ShapeSerializerOptions
}

// ShapeSerializerOptions configures ShapeSerializer.
type ShapeSerializerOptions struct{}

var _ smithy.ShapeSerializer = (*ShapeSerializer)(nil)

func NewShapeSerializer(opts ...func(*ShapeSerializerOptions)) *ShapeSerializer {
	return &ShapeSerializer{
		root: smithyjson.NewEncoder(),
	}
}

func (ss *ShapeSerializer) Bytes() []byte {
	return ss.root.Bytes()
}

func (ss *ShapeSerializer) WriteInt8Ptr(s *smithy.Schema, v *int8) {
	if v != nil {
		ss.WriteInt8(s, *v)
	}
}

func (ss *ShapeSerializer) WriteInt16Ptr(s *smithy.Schema, v *int16) {
	if v != nil {
		ss.WriteInt16(s, *v)
	}
}

func (ss *ShapeSerializer) WriteInt32Ptr(s *smithy.Schema, v *int32) {
	if v != nil {
		ss.WriteInt32(s, *v)
	}
}

func (ss *ShapeSerializer) WriteInt64Ptr(s *smithy.Schema, v *int64) {
	if v != nil {
		ss.WriteInt64(s, *v)
	}
}

func (ss *ShapeSerializer) WriteFloat32Ptr(s *smithy.Schema, v *float32) {
	if v != nil {
		ss.WriteFloat32(s, *v)
	}
}

func (ss *ShapeSerializer) WriteFloat64Ptr(s *smithy.Schema, v *float64) {
	if v != nil {
		ss.WriteFloat64(s, *v)
	}
}

func (ss *ShapeSerializer) WriteBoolPtr(s *smithy.Schema, v *bool) {
	if v != nil {
		ss.WriteBool(s, *v)
	}
}

func (ss *ShapeSerializer) WriteStringPtr(s *smithy.Schema, v *string) {
	if v != nil {
		ss.WriteString(s, *v)
	}
}

func (ss *ShapeSerializer) WriteBool(s *smithy.Schema, v bool) {
	switch enc := ss.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.ID.Member).Boolean(v)
	case *smithyjson.Array:
		enc.Value().Boolean(v)
	default:
		ss.root.Boolean(v)
	}
}

func (ss *ShapeSerializer) WriteInt8(s *smithy.Schema, v int8) {
	switch enc := ss.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.ID.Member).Byte(v)
	case *smithyjson.Array:
		enc.Value().Byte(v)
	default:
		ss.root.Byte(v)
	}
}

func (ss *ShapeSerializer) WriteInt16(s *smithy.Schema, v int16) {
	switch enc := ss.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.ID.Member).Short(v)
	case *smithyjson.Array:
		enc.Value().Short(v)
	default:
		ss.root.Short(v)
	}
}

func (ss *ShapeSerializer) WriteInt32(s *smithy.Schema, v int32) {
	switch enc := ss.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.ID.Member).Integer(v)
	case *smithyjson.Array:
		enc.Value().Integer(v)
	case smithyjson.Value:
		enc.Integer(v)
		ss.head.Pop()
	default:
		ss.root.Integer(v)
	}
}

func (ss *ShapeSerializer) WriteInt64(s *smithy.Schema, v int64) {
	switch enc := ss.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.ID.Member).Long(v)
	case *smithyjson.Array:
		enc.Value().Long(v)
	default:
		ss.root.Long(v)
	}
}

func (ss *ShapeSerializer) WriteString(s *smithy.Schema, v string) {
	switch enc := ss.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.ID.Member).String(v)
	case *smithyjson.Array:
		enc.Value().String(v)
	case smithyjson.Value:
		enc.String(v)
		ss.head.Pop()
	default:
		ss.root.Value.String(v)
	}
}

func (ss *ShapeSerializer) WriteBlob(s *smithy.Schema, v []byte) {
	switch enc := ss.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.ID.Member).Base64EncodeBytes(v)
	case *smithyjson.Array:
		enc.Value().Base64EncodeBytes(v)
	case smithyjson.Value:
		enc.Base64EncodeBytes(v)
		ss.head.Pop()
	default:
		ss.root.Value.Base64EncodeBytes(v)
	}
}

func (ss *ShapeSerializer) WriteList(s *smithy.Schema) {
	switch enc := ss.head.Top().(type) {
	case *smithyjson.Object:
		ss.head.Push(enc.Key(s.ID.Member).Array())
	case *smithyjson.Array:
		ss.head.Push(enc.Value().Array())
	case smithyjson.Value:
		ss.head.Push(enc.Array())
	default:
		ss.head.Push(ss.root.Array())
	}
}

func (ss *ShapeSerializer) CloseList() {
	if enc, ok := ss.head.Top().(*smithyjson.Array); ok {
		enc.Close()
		ss.head.Pop()
	}
}

func (ss *ShapeSerializer) WriteMap(s *smithy.Schema) {
	switch enc := ss.head.Top().(type) {
	case *smithyjson.Object:
		ss.head.Push(enc.Key(s.ID.Member).Object())
	case *smithyjson.Array:
		ss.head.Push(enc.Value().Object())
	case smithyjson.Value:
		ss.head.Push(enc.Object())
	default:
		ss.head.Push(ss.root.Object())
	}
}

func (ss *ShapeSerializer) WriteKey(s *smithy.Schema, key string) {
	if enc, ok := ss.head.Top().(*smithyjson.Object); ok {
		ss.head.Push(enc.Key(key))
	}
}

func (ss *ShapeSerializer) CloseMap() {
	if enc, ok := ss.head.Top().(*smithyjson.Object); ok {
		enc.Close()
		ss.head.Pop()

		// if this is a map _inside_ a map, pop off the underlying key encoder
		// as well (for scalar values that's not necessarily since we can
		// deterministically do it there)
		if _, ok := ss.head.Top().(smithyjson.Value); ok {
			ss.head.Pop()
		}
	}
}

func (ss *ShapeSerializer) WriteFloat32(s *smithy.Schema, v float32) {
	switch enc := ss.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.ID.Member).Float(v)
	case *smithyjson.Array:
		enc.Value().Float(v)
	default:
		ss.root.Float(v)
	}
}

func (ss *ShapeSerializer) WriteFloat64(s *smithy.Schema, v float64) {
	switch enc := ss.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.ID.Member).Double(v)
	case *smithyjson.Array:
		enc.Value().Double(v)
	default:
		ss.root.Double(v)
	}
}

func (ss *ShapeSerializer) WriteTime(s *smithy.Schema, v time.Time) {
	panic("TODO")
}

func (ss *ShapeSerializer) WriteTimePtr(s *smithy.Schema, v *time.Time) {
	if v != nil {
		ss.WriteTime(s, *v)
	}
}

func (s *ShapeSerializer) WriteUnion(schema, variant *smithy.Schema, v smithy.Serializable) {
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		s.head.Push(enc.Key(schema.ID.Member).Object())
	case *smithyjson.Array:
		s.head.Push(enc.Value().Object())
	case smithyjson.Value:
		s.head.Push(enc.Object())
	default:
		s.head.Push(s.root.Object())
	}

	top := s.head.Top().(*smithyjson.Object)
	s.head.Push(top.Key(variant.ID.Member))

	v.Serialize(s)

	s.head.Pop()
}

func (ss *ShapeSerializer) WriteStruct(s *smithy.Schema, v smithy.Serializable) {
	fmt.Println("nil?")
	if v == nil {
		fmt.Println("yes")
		return
	}
	fmt.Println("no")

	switch enc := ss.head.Top().(type) {
	case *smithyjson.Object:
		ss.head.Push(enc.Key(s.ID.Member).Object())
	case *smithyjson.Array:
		ss.head.Push(enc.Value().Object())
	case smithyjson.Value:
		ss.head.Push(enc.Object())
	default:
		ss.head.Push(ss.root.Object())
	}

	v.Serialize(ss)
	ss.head.Pop()
}

func (ss *ShapeSerializer) WriteNil(s *smithy.Schema) {
	switch enc := ss.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.ID.Member).Null()
	case *smithyjson.Array:
		enc.Value().Null()
	case smithyjson.Value:
		enc.Null()
		ss.head.Pop()
	default:
		ss.root.Null()
	}
}

// WriteBigInteger is unimplemented and will panic.
func (ss *ShapeSerializer) WriteBigInteger(s *smithy.Schema, v big.Int) {
	panic("unimplemented")
}

// WriteBigDecimal is unimplemented and will panic.
func (ss *ShapeSerializer) WriteBigDecimal(s *smithy.Schema, v big.Float) {
	panic("unimplemented")
}

// WriteDocument writes the opaque value of a document type to JSON.
func (s *ShapeSerializer) WriteDocument(schema *smithy.Schema, v smithy.Document2) {
	vv := v.Value()
	denc := smithydocumentjson.NewEncoder()
	p, _ := denc.Encode(vv)

	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(schema.ID.Member).Write(p)
	case *smithyjson.Array:
		enc.Value().Write(p)
	case smithyjson.Value:
		enc.Write(p)
		s.head.Pop()
	default:
		s.root.Write(p)
	}
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

func (s *stack) Len() int {
	return len(s.values)
}
