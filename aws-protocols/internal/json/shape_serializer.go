package json

import (
	"fmt"
	"math/big"
	"time"

	"github.com/aws/smithy-go"
	smithydocumentjson "github.com/aws/smithy-go/document/json"
	smithyjson "github.com/aws/smithy-go/encoding/json"
	smithytime "github.com/aws/smithy-go/time"
	"github.com/aws/smithy-go/traits"
)

// ShapeSerializer implements marshaling of Smithy shapes to JSON.
type ShapeSerializer struct {
	root *smithyjson.Encoder
	head stack

	opts ShapeSerializerOptions
}

// ShapeSerializerOptions configures ShapeSerializer.
type ShapeSerializerOptions struct {
	// Controls whether scalar zero values (numbers, strings, bools) are
	// written. If false (the default), zero values are not encoded.
	//
	// Pointer write methods are NOT affected by this option.
	WriteZeroValues bool
}

var _ smithy.ShapeSerializer = (*ShapeSerializer)(nil)

func NewShapeSerializer(opts ...func(*ShapeSerializerOptions)) *ShapeSerializer {
	o := ShapeSerializerOptions{}
	for _, fn := range opts {
		fn(&o)
	}
	return &ShapeSerializer{
		root: smithyjson.NewEncoder(),
		opts: o,
	}
}

func (s *ShapeSerializer) Bytes() []byte {
	return s.root.Bytes()
}

func (s *ShapeSerializer) WriteInt8Ptr(schema *smithy.Schema, v *int8) {
	if v != nil {
		s.withWriteZero(func() { s.WriteInt8(schema, *v) })
	}
}

func (s *ShapeSerializer) WriteInt16Ptr(schema *smithy.Schema, v *int16) {
	if v != nil {
		s.withWriteZero(func() { s.WriteInt16(schema, *v) })
	}
}

func (s *ShapeSerializer) WriteInt32Ptr(schema *smithy.Schema, v *int32) {
	if v != nil {
		s.withWriteZero(func() { s.WriteInt32(schema, *v) })
	}
}

func (s *ShapeSerializer) WriteInt64Ptr(schema *smithy.Schema, v *int64) {
	if v != nil {
		s.withWriteZero(func() { s.WriteInt64(schema, *v) })
	}
}

func (s *ShapeSerializer) WriteFloat32Ptr(schema *smithy.Schema, v *float32) {
	if v != nil {
		s.withWriteZero(func() { s.WriteFloat32(schema, *v) })
	}
}

func (s *ShapeSerializer) WriteFloat64Ptr(schema *smithy.Schema, v *float64) {
	if v != nil {
		s.withWriteZero(func() { s.WriteFloat64(schema, *v) })
	}
}

func (s *ShapeSerializer) WriteBoolPtr(schema *smithy.Schema, v *bool) {
	if v != nil {
		s.withWriteZero(func() { s.WriteBool(schema, *v) })
	}
}

func (s *ShapeSerializer) WriteStringPtr(schema *smithy.Schema, v *string) {
	if v != nil {
		s.withWriteZero(func() { s.WriteString(schema, *v) })
	}
}

func (s *ShapeSerializer) withWriteZero(fn func()) {
	prev := s.opts.WriteZeroValues
	s.opts.WriteZeroValues = true
	fn()
	s.opts.WriteZeroValues = prev
}

func (s *ShapeSerializer) WriteBool(schema *smithy.Schema, v bool) {
	if !s.opts.WriteZeroValues && !v {
		return
	}
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(schema.ID.Member).Boolean(v)
	case *smithyjson.Array:
		enc.Value().Boolean(v)
	default:
		s.root.Boolean(v)
	}
}

func (s *ShapeSerializer) WriteInt8(schema *smithy.Schema, v int8) {
	if !s.opts.WriteZeroValues && v == 0 {
		return
	}
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(schema.ID.Member).Byte(v)
	case *smithyjson.Array:
		enc.Value().Byte(v)
	default:
		s.root.Byte(v)
	}
}

func (s *ShapeSerializer) WriteInt16(schema *smithy.Schema, v int16) {
	if !s.opts.WriteZeroValues && v == 0 {
		return
	}
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(schema.ID.Member).Short(v)
	case *smithyjson.Array:
		enc.Value().Short(v)
	default:
		s.root.Short(v)
	}
}

func (s *ShapeSerializer) WriteInt32(schema *smithy.Schema, v int32) {
	if !s.opts.WriteZeroValues && v == 0 {
		return
	}
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(schema.ID.Member).Integer(v)
	case *smithyjson.Array:
		enc.Value().Integer(v)
	case smithyjson.Value:
		enc.Integer(v)
		s.head.Pop()
	default:
		s.root.Integer(v)
	}
}

func (s *ShapeSerializer) WriteInt64(schema *smithy.Schema, v int64) {
	if !s.opts.WriteZeroValues && v == 0 {
		return
	}
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(schema.ID.Member).Long(v)
	case *smithyjson.Array:
		enc.Value().Long(v)
	default:
		s.root.Long(v)
	}
}

func (s *ShapeSerializer) WriteFloat32(schema *smithy.Schema, v float32) {
	if !s.opts.WriteZeroValues && v == 0 {
		return
	}
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(schema.ID.Member).Float(v)
	case *smithyjson.Array:
		enc.Value().Float(v)
	default:
		s.root.Float(v)
	}
}

func (s *ShapeSerializer) WriteFloat64(schema *smithy.Schema, v float64) {
	if !s.opts.WriteZeroValues && v == 0 {
		return
	}
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(schema.ID.Member).Double(v)
	case *smithyjson.Array:
		enc.Value().Double(v)
	default:
		s.root.Double(v)
	}
}

func (s *ShapeSerializer) WriteString(schema *smithy.Schema, v string) {
	if !s.opts.WriteZeroValues && v == "" {
		return
	}
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(schema.ID.Member).String(v)
	case *smithyjson.Array:
		enc.Value().String(v)
	case smithyjson.Value:
		enc.String(v)
		s.head.Pop()
	default:
		s.root.Value.String(v)
	}
}

func (s *ShapeSerializer) WriteBlob(schema *smithy.Schema, v []byte) {
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(schema.ID.Member).Base64EncodeBytes(v)
	case *smithyjson.Array:
		enc.Value().Base64EncodeBytes(v)
	case smithyjson.Value:
		enc.Base64EncodeBytes(v)
		s.head.Pop()
	default:
		s.root.Value.Base64EncodeBytes(v)
	}
}

func (s *ShapeSerializer) WriteList(schema *smithy.Schema) {
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		s.head.Push(enc.Key(schema.ID.Member).Array())
	case *smithyjson.Array:
		s.head.Push(enc.Value().Array())
	case smithyjson.Value:
		s.head.Push(enc.Array())
	default:
		s.head.Push(s.root.Array())
	}
}

func (s *ShapeSerializer) CloseList() {
	if enc, ok := s.head.Top().(*smithyjson.Array); ok {
		enc.Close()
		s.head.Pop()
	}
}

func (s *ShapeSerializer) WriteMap(schema *smithy.Schema) {
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
}

func (s *ShapeSerializer) WriteKey(schema *smithy.Schema, key string) {
	if enc, ok := s.head.Top().(*smithyjson.Object); ok {
		s.head.Push(enc.Key(key))
	}
}

func (s *ShapeSerializer) CloseMap() {
	if enc, ok := s.head.Top().(*smithyjson.Object); ok {
		enc.Close()
		s.head.Pop()

		// if this is a map _inside_ a map, pop off the underlying key encoder
		// as well (for scalar values that's not necessarily since we can
		// deterministically do it there)
		if _, ok := s.head.Top().(smithyjson.Value); ok {
			s.head.Pop()
		}
	}
}

func (s *ShapeSerializer) WriteTime(schema *smithy.Schema, v time.Time) {
	format := "epoch-seconds"
	if t, ok := smithy.SchemaTrait[*traits.TimestampFormat](schema); ok {
		format = t.Format
	}

	switch format {
	case "date-time":
		s.WriteString(schema, smithytime.FormatDateTime(v))
	case "http-date":
		s.WriteString(schema, smithytime.FormatHTTPDate(v))
	default:
		s.WriteFloat64(schema, smithytime.FormatEpochSeconds(v))
	}
}

func (s *ShapeSerializer) WriteTimePtr(schema *smithy.Schema, v *time.Time) {
	if v != nil {
		s.WriteTime(schema, *v)
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

func (s *ShapeSerializer) WriteStruct(schema *smithy.Schema, v smithy.Serializable) {
	fmt.Println("nil?")
	if v == nil {
		fmt.Println("yes")
		return
	}
	fmt.Println("no")

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

	v.Serialize(s)
	s.head.Pop()
}

func (s *ShapeSerializer) WriteNil(schema *smithy.Schema) {
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(schema.ID.Member).Null()
	case *smithyjson.Array:
		enc.Value().Null()
	case smithyjson.Value:
		enc.Null()
		s.head.Pop()
	default:
		s.root.Null()
	}
}

// WriteBigInteger is unimplemented and will panic.
func (s *ShapeSerializer) WriteBigInteger(schema *smithy.Schema, v big.Int) {
	panic("unimplemented")
}

// WriteBigDecimal is unimplemented and will panic.
func (s *ShapeSerializer) WriteBigDecimal(schema *smithy.Schema, v big.Float) {
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
