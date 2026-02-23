package httpbinding

import (
	"encoding/base64"
	"math/big"
	"strconv"
	"time"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/encoding"
	"github.com/aws/smithy-go/traits"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// ShapeSerializer serializes shapes to HTTP request components based on HTTP binding traits.
type ShapeSerializer struct {
	req     *smithyhttp.Request
	scratch []byte
}

var _ smithy.ShapeSerializer = (*ShapeSerializer)(nil)

// New returns a new HTTP binding shape serializer.
func New(req *smithyhttp.Request) *ShapeSerializer {
	return &ShapeSerializer{
		req:     req,
		scratch: make([]byte, 64),
	}
}

// Bytes returns nil as HTTP binding serialization writes directly to the request.
func (s *ShapeSerializer) Bytes() []byte {
	return nil
}

// WriteInt8 serializes an int8 value to HTTP headers or query parameters.
func (s *ShapeSerializer) WriteInt8(schema *smithy.Schema, v int8) {
	s.writeInt(schema, int64(v))
}

// WriteInt16 serializes an int16 value to HTTP headers or query parameters.
func (s *ShapeSerializer) WriteInt16(schema *smithy.Schema, v int16) {
	s.writeInt(schema, int64(v))
}

// WriteInt32 serializes an int32 value to HTTP headers or query parameters.
func (s *ShapeSerializer) WriteInt32(schema *smithy.Schema, v int32) {
	s.writeInt(schema, int64(v))
}

// WriteInt64 serializes an int64 value to HTTP headers or query parameters.
func (s *ShapeSerializer) WriteInt64(schema *smithy.Schema, v int64) {
	s.writeInt(schema, v)
}

func (s *ShapeSerializer) writeInt(schema *smithy.Schema, v int64) {
	str := strconv.FormatInt(v, 10)
	if h, ok := smithy.SchemaTrait[*traits.HTTPHeader](schema); ok {
		s.req.Header.Set(h.Name, str)
	} else if q, ok := smithy.SchemaTrait[*traits.HTTPQuery](schema); ok {
		s.req.URL.Query().Set(q.Name, str)
	}
}

// WriteInt8Ptr serializes an int8 pointer to HTTP headers or query parameters.
func (s *ShapeSerializer) WriteInt8Ptr(schema *smithy.Schema, v *int8) {
	if v != nil {
		s.WriteInt8(schema, *v)
	}
}

// WriteInt16Ptr serializes an int16 pointer to HTTP headers or query parameters.
func (s *ShapeSerializer) WriteInt16Ptr(schema *smithy.Schema, v *int16) {
	if v != nil {
		s.WriteInt16(schema, *v)
	}
}

// WriteInt32Ptr serializes an int32 pointer to HTTP headers or query parameters.
func (s *ShapeSerializer) WriteInt32Ptr(schema *smithy.Schema, v *int32) {
	if v != nil {
		s.WriteInt32(schema, *v)
	}
}

// WriteInt64Ptr serializes an int64 pointer to HTTP headers or query parameters.
func (s *ShapeSerializer) WriteInt64Ptr(schema *smithy.Schema, v *int64) {
	if v != nil {
		s.WriteInt64(schema, *v)
	}
}

// WriteFloat32 serializes a float32 value to HTTP headers or query parameters.
func (s *ShapeSerializer) WriteFloat32(schema *smithy.Schema, v float32) {
	s.writeFloat(schema, float64(v), 32)
}

// WriteFloat64 serializes a float64 value to HTTP headers or query parameters.
func (s *ShapeSerializer) WriteFloat64(schema *smithy.Schema, v float64) {
	s.writeFloat(schema, v, 64)
}

func (s *ShapeSerializer) writeFloat(schema *smithy.Schema, v float64, bits int) {
	s.scratch = encoding.EncodeFloat(s.scratch[:0], v, bits)
	str := string(s.scratch)
	if h, ok := smithy.SchemaTrait[*traits.HTTPHeader](schema); ok {
		s.req.Header.Set(h.Name, str)
	} else if q, ok := smithy.SchemaTrait[*traits.HTTPQuery](schema); ok {
		s.req.URL.Query().Set(q.Name, str)
	}
}

// WriteFloat32Ptr serializes a float32 pointer to HTTP headers or query parameters.
func (s *ShapeSerializer) WriteFloat32Ptr(schema *smithy.Schema, v *float32) {
	if v != nil {
		s.WriteFloat32(schema, *v)
	}
}

// WriteFloat64Ptr serializes a float64 pointer to HTTP headers or query parameters.
func (s *ShapeSerializer) WriteFloat64Ptr(schema *smithy.Schema, v *float64) {
	if v != nil {
		s.WriteFloat64(schema, *v)
	}
}

// WriteBool serializes a bool value to HTTP headers or query parameters.
func (s *ShapeSerializer) WriteBool(schema *smithy.Schema, v bool) {
	str := strconv.FormatBool(v)
	if h, ok := smithy.SchemaTrait[*traits.HTTPHeader](schema); ok {
		s.req.Header.Set(h.Name, str)
	} else if q, ok := smithy.SchemaTrait[*traits.HTTPQuery](schema); ok {
		s.req.URL.Query().Set(q.Name, str)
	}
}

// WriteBoolPtr serializes a bool pointer to HTTP headers or query parameters.
func (s *ShapeSerializer) WriteBoolPtr(schema *smithy.Schema, v *bool) {
	if v != nil {
		s.WriteBool(schema, *v)
	}
}

// WriteString serializes a string value to HTTP headers or query parameters.
func (s *ShapeSerializer) WriteString(schema *smithy.Schema, v string) {
	if h, ok := smithy.SchemaTrait[*traits.HTTPHeader](schema); ok {
		s.req.Header.Set(h.Name, v)
	} else if q, ok := smithy.SchemaTrait[*traits.HTTPQuery](schema); ok {
		s.req.URL.Query().Set(q.Name, v)
	}
}

// WriteStringPtr serializes a string pointer to HTTP headers or query parameters.
func (s *ShapeSerializer) WriteStringPtr(schema *smithy.Schema, v *string) {
	if v != nil {
		s.WriteString(schema, *v)
	}
}

// WriteBigInteger panics as BigInteger is not supported.
func (s *ShapeSerializer) WriteBigInteger(schema *smithy.Schema, v big.Int) {
	panic("BigInteger is not supported")
}

// WriteBigDecimal panics as BigDecimal is not supported.
func (s *ShapeSerializer) WriteBigDecimal(schema *smithy.Schema, v big.Float) {
	panic("BigDecimal is not supported")
}

// WriteBlob serializes a blob value to HTTP headers or query parameters as base64.
func (s *ShapeSerializer) WriteBlob(schema *smithy.Schema, v []byte) {
	if h, ok := smithy.SchemaTrait[*traits.HTTPHeader](schema); ok {
		s.req.Header.Set(h.Name, base64.StdEncoding.EncodeToString(v))
	} else if q, ok := smithy.SchemaTrait[*traits.HTTPQuery](schema); ok {
		s.req.URL.Query().Set(q.Name, base64.StdEncoding.EncodeToString(v))
	}
}

// WriteTime serializes a time value to HTTP headers or query parameters.
func (s *ShapeSerializer) WriteTime(schema *smithy.Schema, v time.Time) {
	var str string
	if tf, ok := smithy.SchemaTrait[*traits.TimestampFormat](schema); ok {
		str = formatTime(v, tf.Format)
	} else {
		str = v.Format(time.RFC3339)
	}
	if h, ok := smithy.SchemaTrait[*traits.HTTPHeader](schema); ok {
		s.req.Header.Set(h.Name, str)
	} else if q, ok := smithy.SchemaTrait[*traits.HTTPQuery](schema); ok {
		s.req.URL.Query().Set(q.Name, str)
	}
}

func formatTime(v time.Time, format string) string {
	switch format {
	case "date-time":
		return v.Format(time.RFC3339)
	case "http-date":
		return v.Format(time.RFC1123)
	case "epoch-seconds":
		return strconv.FormatInt(v.Unix(), 10)
	default:
		return v.Format(time.RFC3339)
	}
}

func (s *ShapeSerializer) WriteStruct(schema *smithy.Schema, v smithy.Serializable) {}

func (s *ShapeSerializer) WriteNil(schema *smithy.Schema) {}

func (s *ShapeSerializer) WriteList(schema *smithy.Schema) {}

func (s *ShapeSerializer) CloseList() {}

func (s *ShapeSerializer) WriteMap(schema *smithy.Schema) {}

func (s *ShapeSerializer) WriteKey(schema *smithy.Schema, key string) {}

func (s *ShapeSerializer) CloseMap() {}
