package json

import (
	"fmt"
	"math/big"
	"time"

	"github.com/aws/smithy-go"
)

// ShapeSerializer implements marshaling of Smithy shapes to JSON.
type ShapeSerializer struct {
	root *Encoder
	head stack
}

var _ smithy.ShapeSerializer = (*ShapeSerializer)(nil)

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
	case *Object:
		enc.Key(s.ID.Member).Boolean(v)
	case *Array:
		enc.Value().Boolean(v)
	default:
		ss.root.Boolean(v)
	}
}

func (ss *ShapeSerializer) WriteInt8(s *smithy.Schema, v int8) {
	switch enc := ss.head.Top().(type) {
	case *Object:
		enc.Key(s.ID.Member).Byte(v)
	case *Array:
		enc.Value().Byte(v)
	default:
		ss.root.Byte(v)
	}
}

func (ss *ShapeSerializer) WriteInt16(s *smithy.Schema, v int16) {
	switch enc := ss.head.Top().(type) {
	case *Object:
		enc.Key(s.ID.Member).Short(v)
	case *Array:
		enc.Value().Short(v)
	default:
		ss.root.Short(v)
	}
}

func (ss *ShapeSerializer) WriteInt32(s *smithy.Schema, v int32) {
	switch enc := ss.head.Top().(type) {
	case *Object:
		enc.Key(s.ID.Member).Integer(v)
	case *Array:
		enc.Value().Integer(v)
	case Value:
		enc.Integer(v)
		ss.head.Pop()
	default:
		ss.root.Integer(v)
	}
}

func (ss *ShapeSerializer) WriteInt64(s *smithy.Schema, v int64) {
	switch enc := ss.head.Top().(type) {
	case *Object:
		enc.Key(s.ID.Member).Long(v)
	case *Array:
		enc.Value().Long(v)
	default:
		ss.root.Long(v)
	}
}

func (ss *ShapeSerializer) WriteString(s *smithy.Schema, v string) {
	switch enc := ss.head.Top().(type) {
	case *Object:
		enc.Key(s.ID.Member).String(v)
	case *Array:
		enc.Value().String(v)
	case Value:
		enc.String(v)
		ss.head.Pop()
	default:
		ss.root.Value.String(v)
	}
}

func (ss *ShapeSerializer) WriteBlob(s *smithy.Schema, v []byte) {
	switch enc := ss.head.Top().(type) {
	case *Object:
		enc.Key(s.ID.Member).Base64EncodeBytes(v)
	case *Array:
		enc.Value().Base64EncodeBytes(v)
	case Value:
		enc.Base64EncodeBytes(v)
		ss.head.Pop()
	default:
		ss.root.Value.Base64EncodeBytes(v)
	}
}

func (ss *ShapeSerializer) WriteList(s *smithy.Schema) {
	// TODO
}

func (ss *ShapeSerializer) CloseList() {
	// TODO
}

func (ss *ShapeSerializer) WriteMap(s *smithy.Schema) {
	switch enc := ss.head.Top().(type) {
	case *Object:
		ss.head.Push(enc.Key(s.ID.Member).Object())
	case *Array:
		ss.head.Push(enc.Value().Object())
	case Value:
		ss.head.Push(enc.Object())
	default:
		ss.head.Push(ss.root.Object())
	}
}

func (ss *ShapeSerializer) WriteKey(s *smithy.Schema, key string) {
	if enc, ok := ss.head.Top().(*Object); ok {
		ss.head.Push(enc.Key(key))
	}
}

func printstack(s stack) {
	for _, v := range s.values {
		fmt.Printf("%T ", v)
	}
	fmt.Println()
}

func (ss *ShapeSerializer) CloseMap() {
	if enc, ok := ss.head.Top().(*Object); ok {
		enc.Close()
		ss.head.Pop()

		// if this is a map _inside_ a map, pop off the underlying key encoder
		// as well (for scalar values that's not necessarily since we can
		// deterministically do it there)
		if _, ok := ss.head.Top().(Value); ok {
			ss.head.Pop()
		}
	}
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
