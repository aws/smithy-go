package json

import (
	"math"
	"math/big"
	"time"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/document"
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

	// Controls whether the @jsonName trait is used to
	// determine JSON object keys. If false (the default), the member
	// name is used as-is.
	//
	// How this is set in practice depends on the protocol. RPC-style protocols
	// like awsjson10 ignore @jsonName, REST-style protocols like restjson1
	// respect it.
	UseJSONName bool
}

var _ smithy.ShapeSerializer = (*ShapeSerializer)(nil)

// NewShapeSerializer creates a new ShapeSerializer.
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

// Bytes returns the serialized JSON bytes.
func (s *ShapeSerializer) Bytes() []byte {
	return s.root.Bytes()
}

// WriteInt8Ptr implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt8Ptr(schema *smithy.Schema, v *int8) {
	if v != nil {
		s.withWriteZero(func() { s.WriteInt8(schema, *v) })
	}
}

// WriteInt16Ptr implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt16Ptr(schema *smithy.Schema, v *int16) {
	if v != nil {
		s.withWriteZero(func() { s.WriteInt16(schema, *v) })
	}
}

// WriteInt32Ptr implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt32Ptr(schema *smithy.Schema, v *int32) {
	if v != nil {
		s.withWriteZero(func() { s.WriteInt32(schema, *v) })
	}
}

// WriteInt64Ptr implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt64Ptr(schema *smithy.Schema, v *int64) {
	if v != nil {
		s.withWriteZero(func() { s.WriteInt64(schema, *v) })
	}
}

// WriteFloat32Ptr implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteFloat32Ptr(schema *smithy.Schema, v *float32) {
	if v != nil {
		s.withWriteZero(func() { s.WriteFloat32(schema, *v) })
	}
}

// WriteFloat64Ptr implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteFloat64Ptr(schema *smithy.Schema, v *float64) {
	if v != nil {
		s.withWriteZero(func() { s.WriteFloat64(schema, *v) })
	}
}

// WriteBoolPtr implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteBoolPtr(schema *smithy.Schema, v *bool) {
	if v != nil {
		s.withWriteZero(func() { s.WriteBool(schema, *v) })
	}
}

// WriteStringPtr implements [smithy.ShapeSerializer].
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

func (s *ShapeSerializer) skipZeroValue() bool {
	if s.opts.WriteZeroValues {
		return false
	}
	switch s.head.Top().(type) {
	case *smithyjson.Array, smithyjson.Value:
		return false
	default:
		return true
	}
}

// WriteBool implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteBool(schema *smithy.Schema, v bool) {
	if !v && s.skipZeroValue() {
		return
	}
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.jsonMemberName(schema)).Boolean(v)
	case *smithyjson.Array:
		enc.Value().Boolean(v)
	case smithyjson.Value:
		enc.Boolean(v)
		s.head.Pop()
	default:
		s.root.Boolean(v)
	}
}

// WriteInt8 implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt8(schema *smithy.Schema, v int8) {
	if v == 0 && s.skipZeroValue() {
		return
	}
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.jsonMemberName(schema)).Byte(v)
	case *smithyjson.Array:
		enc.Value().Byte(v)
	case smithyjson.Value:
		enc.Byte(v)
		s.head.Pop()
	default:
		s.root.Byte(v)
	}
}

// WriteInt16 implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt16(schema *smithy.Schema, v int16) {
	if v == 0 && s.skipZeroValue() {
		return
	}
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.jsonMemberName(schema)).Short(v)
	case *smithyjson.Array:
		enc.Value().Short(v)
	case smithyjson.Value:
		enc.Short(v)
		s.head.Pop()
	default:
		s.root.Short(v)
	}
}

// WriteInt32 implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt32(schema *smithy.Schema, v int32) {
	if v == 0 && s.skipZeroValue() {
		return
	}
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.jsonMemberName(schema)).Integer(v)
	case *smithyjson.Array:
		enc.Value().Integer(v)
	case smithyjson.Value:
		enc.Integer(v)
		s.head.Pop()
	default:
		s.root.Integer(v)
	}
}

// WriteInt64 implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt64(schema *smithy.Schema, v int64) {
	if v == 0 && s.skipZeroValue() {
		return
	}
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.jsonMemberName(schema)).Long(v)
	case *smithyjson.Array:
		enc.Value().Long(v)
	case smithyjson.Value:
		enc.Long(v)
		s.head.Pop()
	default:
		s.root.Long(v)
	}
}

// WriteFloat32 implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteFloat32(schema *smithy.Schema, v float32) {
	if v == 0 && s.skipZeroValue() {
		return
	}

	var jv smithyjson.Value
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		jv = enc.Key(s.jsonMemberName(schema))
	case *smithyjson.Array:
		jv = enc.Value()
	case smithyjson.Value:
		jv = enc
		s.head.Pop()
	default:
		s.root.Float(v)
		return
	}

	if math.IsInf(float64(v), 1) {
		jv.String("Infinity")
	} else if math.IsInf(float64(v), -1) {
		jv.String("-Infinity")
	} else if math.IsNaN(float64(v)) {
		jv.String("NaN")
	} else {
		jv.Float(v)
	}
}
func (s *ShapeSerializer) WriteFloat64(schema *smithy.Schema, v float64) {
	if v == 0 && s.skipZeroValue() {
		return
	}

	var jv smithyjson.Value
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		jv = enc.Key(s.jsonMemberName(schema))
	case *smithyjson.Array:
		jv = enc.Value()
	case smithyjson.Value:
		jv = enc
		s.head.Pop()
	default:
		s.root.Double(v)
		return
	}

	if math.IsInf(v, 1) {
		jv.String("Infinity")
	} else if math.IsInf(v, -1) {
		jv.String("-Infinity")
	} else if math.IsNaN(v) {
		jv.String("NaN")
	} else {
		jv.Double(v)
	}
}

// WriteString implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteString(schema *smithy.Schema, v string) {
	if v == "" && s.skipZeroValue() {
		return
	}
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.jsonMemberName(schema)).String(v)
	case *smithyjson.Array:
		enc.Value().String(v)
	case smithyjson.Value:
		enc.String(v)
		s.head.Pop()
	default:
		s.root.Value.String(v)
	}
}

// WriteBlob implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteBlob(schema *smithy.Schema, v []byte) {
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.jsonMemberName(schema)).Base64EncodeBytes(v)
	case *smithyjson.Array:
		enc.Value().Base64EncodeBytes(v)
	case smithyjson.Value:
		enc.Base64EncodeBytes(v)
		s.head.Pop()
	default:
		s.root.Value.Base64EncodeBytes(v)
	}
}

// WriteList implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteList(schema *smithy.Schema) {
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		s.head.Push(enc.Key(s.jsonMemberName(schema)).Array())
	case *smithyjson.Array:
		s.head.Push(enc.Value().Array())
	case smithyjson.Value:
		s.head.Pop()
		s.head.Push(enc.Array())
	default:
		s.head.Push(s.root.Array())
	}
}

// CloseList implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) CloseList() {
	if enc, ok := s.head.Top().(*smithyjson.Array); ok {
		enc.Close()
		s.head.Pop()
	}
}

// WriteMap implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteMap(schema *smithy.Schema) {
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		s.head.Push(enc.Key(s.jsonMemberName(schema)).Object())
	case *smithyjson.Array:
		s.head.Push(enc.Value().Object())
	case smithyjson.Value:
		s.head.Pop()
		s.head.Push(enc.Object())
	default:
		s.head.Push(s.root.Object())
	}
}

// WriteKey implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteKey(schema *smithy.Schema, key string) {
	if enc, ok := s.head.Top().(*smithyjson.Object); ok {
		s.head.Push(enc.Key(key))
	}
}

// CloseMap implements [smithy.ShapeSerializer].
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

// WriteTime implements [smithy.ShapeSerializer].
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

// WriteTimePtr implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteTimePtr(schema *smithy.Schema, v *time.Time) {
	if v != nil {
		s.WriteTime(schema, *v)
	}
}

// WriteUnion implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteUnion(schema, variant *smithy.Schema, v smithy.Serializable) {
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		s.head.Push(enc.Key(s.jsonMemberName(schema)).Object())
	case *smithyjson.Array:
		s.head.Push(enc.Value().Object())
	case smithyjson.Value:
		s.head.Pop()
		s.head.Push(enc.Object())
	default:
		s.head.Push(s.root.Object())
	}

	top := s.head.Top().(*smithyjson.Object)
	s.head.Push(top.Key(s.jsonMemberName(variant)))

	v.Serialize(s)

	top.Close()
	s.head.Pop()
}

// WriteStruct implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteStruct(schema *smithy.Schema, v smithy.Serializable) {
	if v == nil {
		return
	}

	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		s.head.Push(enc.Key(s.jsonMemberName(schema)))
	case *smithyjson.Array:
		s.head.Push(enc.Value())
	}

	v.Serialize(s)
}

// WriteNil implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteNil(schema *smithy.Schema) {
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.jsonMemberName(schema)).Null()
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

// WriteDocument writes a document value to JSON.
func (s *ShapeSerializer) WriteDocument(schema *smithy.Schema, v document.Value) {
	switch vv := v.(type) {
	case document.Null:
		s.WriteNil(schema)
	case document.Boolean:
		s.WriteBool(schema, bool(vv))
	case document.Number:
		s.writeDocumentRaw(schema, []byte(vv))
	case document.String:
		s.WriteString(schema, string(vv))
	case document.Blob:
		s.WriteBlob(schema, []byte(vv))
	case document.Timestamp:
		s.WriteTime(schema, time.Time(vv))
	case document.List:
		s.WriteList(schema)
		for _, item := range vv {
			s.WriteDocument(schema.ListMember(), item)
		}
		s.CloseList()
	case document.Map:
		s.WriteMap(schema)
		for k, item := range vv {
			s.WriteKey(schema.MapKey(), k)
			s.WriteDocument(schema.MapValue(), item)
		}
		s.CloseMap()
	case document.Structure:
		s.WriteMap(schema)
		for k, item := range vv.Members {
			s.WriteKey(nil, k)
			s.WriteDocument(nil, item)
		}
		s.CloseMap()
	case document.Opaque:
		s.writeOpaqueDocument(schema, vv.Value)
	case *document.Opaque:
		s.writeOpaqueDocument(schema, vv.Value)
	}
}

func (s *ShapeSerializer) writeOpaqueDocument(schema *smithy.Schema, v any) {
	if m, ok := v.(document.Marshaler); ok {
		p, _ := m.MarshalSmithyDocument()
		s.writeDocumentRaw(schema, p)
		return
	}
	denc := smithydocumentjson.NewEncoder()

	// TODO(serde2): we should expose an alternative Encode() API that
	// explicitly does not return errors since schema-serde Serialize is
	// errorless
	p, _ := denc.Encode(v)

	s.writeDocumentRaw(schema, p)
}

func (s *ShapeSerializer) writeDocumentRaw(schema *smithy.Schema, p []byte) {
	switch enc := s.head.Top().(type) {
	case *smithyjson.Object:
		enc.Key(s.jsonMemberName(schema)).Write(p)
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

// jsonMemberName returns the JSON key for a schema member, using the
// jsonName trait if UseJSONName is enabled, otherwise the member name.
func (s *ShapeSerializer) jsonMemberName(schema *smithy.Schema) string {
	if s.opts.UseJSONName {
		if jn, ok := smithy.SchemaTrait[*traits.JSONName](schema); ok {
			return jn.Name
		}
	}
	return schema.MemberName()
}
