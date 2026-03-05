// Package httpbinding implements a ShapeSerializer that routes struct members
// to HTTP binding locations (header, query, URI label, payload) based on
// their schema traits.
package httpbinding

import (
	"fmt"
	"math/big"
	"strconv"
	"time"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/document"
	httpbinding "github.com/aws/smithy-go/encoding/httpbinding"
	smithytime "github.com/aws/smithy-go/time"
	smithyhttp "github.com/aws/smithy-go/transport/http"
	"github.com/aws/smithy-go/traits"
)

// Serializer is a ShapeSerializer that routes top-level input struct members
// to their HTTP binding locations. Members without HTTP binding traits are
// serialized to the body using the provided body serializer.
type Serializer struct {
	Request *smithyhttp.Request
	Encoder *httpbinding.Encoder
	Body    smithy.ShapeSerializer

	// PayloadBytes holds raw bytes for httpPayload blob/string members.
	// When set, the protocol should use these as the body instead of
	// Body.Bytes(), and set the appropriate content type.
	PayloadBytes []byte
	// PayloadContentType is the content type for a raw payload.
	PayloadContentType string

	// state for httpPrefixHeaders / httpQueryParams map serialization
	mapMode    mapBindingMode
	mapPrefix  string // prefix for httpPrefixHeaders
	currentKey string // current map key

	// state for list serialization in HTTP bindings
	listMode listBindingMode
	listName string // header or query param name for list binding
}

type mapBindingMode int

const (
	mapModeNone mapBindingMode = iota
	mapModePrefixHeaders
	mapModeQueryParams
)

type listBindingMode int

const (
	listModeNone listBindingMode = iota
	listModeHeader
	listModeQuery
)

var _ smithy.ShapeSerializer = (*Serializer)(nil)

// Bytes returns the serialized body bytes.
func (s *Serializer) Bytes() []byte {
	return s.Body.Bytes()
}

func isHTTPHeader(schema *smithy.Schema) (*traits.HTTPHeader, bool) {
	return smithy.SchemaTrait[*traits.HTTPHeader](schema)
}

func isHTTPLabel(schema *smithy.Schema) bool {
	_, ok := smithy.SchemaTrait[*traits.HTTPLabel](schema)
	return ok
}

func isHTTPQuery(schema *smithy.Schema) (*traits.HTTPQuery, bool) {
	return smithy.SchemaTrait[*traits.HTTPQuery](schema)
}

func isHTTPPayload(schema *smithy.Schema) bool {
	_, ok := smithy.SchemaTrait[*traits.HTTPPayload](schema)
	return ok
}

func isHTTPPrefixHeaders(schema *smithy.Schema) (*traits.HTTPPrefixHeaders, bool) {
	return smithy.SchemaTrait[*traits.HTTPPrefixHeaders](schema)
}

func isHTTPQueryParams(schema *smithy.Schema) bool {
	_, ok := smithy.SchemaTrait[*traits.HTTPQueryParams](schema)
	return ok
}

// WriteString implements [smithy.ShapeSerializer].
func (s *Serializer) WriteString(schema *smithy.Schema, v string) {
	if v == "" {
		return
	}
	switch s.mapMode {
	case mapModePrefixHeaders:
		s.Encoder.SetHeader(s.mapPrefix + s.currentKey).String(v)
		return
	case mapModeQueryParams:
		s.Encoder.SetQuery(s.currentKey).String(v)
		return
	}
	switch s.listMode {
	case listModeHeader:
		s.Encoder.AddHeader(s.listName).String(v)
		return
	case listModeQuery:
		s.Encoder.AddQuery(s.listName).String(v)
		return
	}
	if isHTTPPayload(schema) {
		s.PayloadBytes = []byte(v)
		s.PayloadContentType = "text/plain"
		return
	}
	if h, ok := isHTTPHeader(schema); ok {
		s.Encoder.SetHeader(h.Name).String(v)
	} else if isHTTPLabel(schema) {
		s.Encoder.SetURI(schema.MemberName()).String(v)
	} else if q, ok := isHTTPQuery(schema); ok {
		s.Encoder.SetQuery(q.Name).String(v)
	} else {
		s.Body.WriteString(schema, v)
	}
}

// WriteStringPtr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteStringPtr(schema *smithy.Schema, v *string) {
	if v != nil {
		s.WriteString(schema, *v)
	}
}

// WriteBool implements [smithy.ShapeSerializer].
func (s *Serializer) WriteBool(schema *smithy.Schema, v bool) {
	if s.listMode == listModeHeader {
		s.Encoder.AddHeader(s.listName).Boolean(v)
		return
	}
	if s.listMode == listModeQuery {
		s.Encoder.AddQuery(s.listName).Boolean(v)
		return
	}
	if h, ok := isHTTPHeader(schema); ok {
		s.Encoder.SetHeader(h.Name).Boolean(v)
	} else if q, ok := isHTTPQuery(schema); ok {
		s.Encoder.SetQuery(q.Name).Boolean(v)
	} else {
		s.Body.WriteBool(schema, v)
	}
}

// WriteBoolPtr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteBoolPtr(schema *smithy.Schema, v *bool) {
	if v != nil {
		s.WriteBool(schema, *v)
	}
}

// WriteInt8 implements [smithy.ShapeSerializer].
func (s *Serializer) WriteInt8(schema *smithy.Schema, v int8) {
	if s.listMode == listModeHeader {
		s.Encoder.AddHeader(s.listName).Byte(v)
		return
	}
	if s.listMode == listModeQuery {
		s.Encoder.AddQuery(s.listName).Byte(v)
		return
	}
	if h, ok := isHTTPHeader(schema); ok {
		s.Encoder.SetHeader(h.Name).Byte(v)
	} else if isHTTPLabel(schema) {
		s.Encoder.SetURI(schema.MemberName()).Byte(v)
	} else if q, ok := isHTTPQuery(schema); ok {
		s.Encoder.SetQuery(q.Name).Byte(v)
	} else {
		s.Body.WriteInt8(schema, v)
	}
}

// WriteInt8Ptr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteInt8Ptr(schema *smithy.Schema, v *int8) {
	if v != nil {
		s.WriteInt8(schema, *v)
	}
}

// WriteInt16 implements [smithy.ShapeSerializer].
func (s *Serializer) WriteInt16(schema *smithy.Schema, v int16) {
	if s.listMode == listModeHeader {
		s.Encoder.AddHeader(s.listName).Short(v)
		return
	}
	if s.listMode == listModeQuery {
		s.Encoder.AddQuery(s.listName).Short(v)
		return
	}
	if h, ok := isHTTPHeader(schema); ok {
		s.Encoder.SetHeader(h.Name).Short(v)
	} else if isHTTPLabel(schema) {
		s.Encoder.SetURI(schema.MemberName()).Short(v)
	} else if q, ok := isHTTPQuery(schema); ok {
		s.Encoder.SetQuery(q.Name).Short(v)
	} else {
		s.Body.WriteInt16(schema, v)
	}
}

// WriteInt16Ptr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteInt16Ptr(schema *smithy.Schema, v *int16) {
	if v != nil {
		s.WriteInt16(schema, *v)
	}
}

// WriteInt32 implements [smithy.ShapeSerializer].
func (s *Serializer) WriteInt32(schema *smithy.Schema, v int32) {
	if s.listMode == listModeHeader {
		s.Encoder.AddHeader(s.listName).Integer(v)
		return
	}
	if s.listMode == listModeQuery {
		s.Encoder.AddQuery(s.listName).Integer(v)
		return
	}
	if h, ok := isHTTPHeader(schema); ok {
		s.Encoder.SetHeader(h.Name).Integer(v)
	} else if isHTTPLabel(schema) {
		s.Encoder.SetURI(schema.MemberName()).Integer(v)
	} else if q, ok := isHTTPQuery(schema); ok {
		s.Encoder.SetQuery(q.Name).Integer(v)
	} else {
		s.Body.WriteInt32(schema, v)
	}
}

// WriteInt32Ptr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteInt32Ptr(schema *smithy.Schema, v *int32) {
	if v != nil {
		s.WriteInt32(schema, *v)
	}
}

// WriteInt64 implements [smithy.ShapeSerializer].
func (s *Serializer) WriteInt64(schema *smithy.Schema, v int64) {
	if s.listMode == listModeHeader {
		s.Encoder.AddHeader(s.listName).Long(v)
		return
	}
	if s.listMode == listModeQuery {
		s.Encoder.AddQuery(s.listName).Long(v)
		return
	}
	if h, ok := isHTTPHeader(schema); ok {
		s.Encoder.SetHeader(h.Name).Long(v)
	} else if isHTTPLabel(schema) {
		s.Encoder.SetURI(schema.MemberName()).Long(v)
	} else if q, ok := isHTTPQuery(schema); ok {
		s.Encoder.SetQuery(q.Name).Long(v)
	} else {
		s.Body.WriteInt64(schema, v)
	}
}

// WriteInt64Ptr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteInt64Ptr(schema *smithy.Schema, v *int64) {
	if v != nil {
		s.WriteInt64(schema, *v)
	}
}

// WriteFloat32 implements [smithy.ShapeSerializer].
func (s *Serializer) WriteFloat32(schema *smithy.Schema, v float32) {
	if s.listMode == listModeHeader {
		s.Encoder.AddHeader(s.listName).Float(v)
		return
	}
	if s.listMode == listModeQuery {
		s.Encoder.AddQuery(s.listName).Float(v)
		return
	}
	if h, ok := isHTTPHeader(schema); ok {
		s.Encoder.SetHeader(h.Name).Float(v)
	} else if q, ok := isHTTPQuery(schema); ok {
		s.Encoder.SetQuery(q.Name).Float(v)
	} else {
		s.Body.WriteFloat32(schema, v)
	}
}

// WriteFloat32Ptr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteFloat32Ptr(schema *smithy.Schema, v *float32) {
	if v != nil {
		s.WriteFloat32(schema, *v)
	}
}

// WriteFloat64 implements [smithy.ShapeSerializer].
func (s *Serializer) WriteFloat64(schema *smithy.Schema, v float64) {
	if s.listMode == listModeHeader {
		s.Encoder.AddHeader(s.listName).Double(v)
		return
	}
	if s.listMode == listModeQuery {
		s.Encoder.AddQuery(s.listName).Double(v)
		return
	}
	if h, ok := isHTTPHeader(schema); ok {
		s.Encoder.SetHeader(h.Name).Double(v)
	} else if q, ok := isHTTPQuery(schema); ok {
		s.Encoder.SetQuery(q.Name).Double(v)
	} else {
		s.Body.WriteFloat64(schema, v)
	}
}

// WriteFloat64Ptr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteFloat64Ptr(schema *smithy.Schema, v *float64) {
	if v != nil {
		s.WriteFloat64(schema, *v)
	}
}

// WriteBlob implements [smithy.ShapeSerializer].
func (s *Serializer) WriteBlob(schema *smithy.Schema, v []byte) {
	if isHTTPPayload(schema) {
		s.PayloadBytes = v
		s.PayloadContentType = "application/octet-stream"
		return
	}
	if h, ok := isHTTPHeader(schema); ok {
		s.Encoder.SetHeader(h.Name).Blob(v)
	} else {
		s.Body.WriteBlob(schema, v)
	}
}

// WriteTime implements [smithy.ShapeSerializer].
func (s *Serializer) WriteTime(schema *smithy.Schema, v time.Time) {
	if s.listMode == listModeHeader {
		s.Encoder.AddHeader(s.listName).String(formatTimestamp(schema, "http-date", v))
		return
	}
	if s.listMode == listModeQuery {
		s.Encoder.AddQuery(s.listName).String(formatTimestamp(schema, "date-time", v))
		return
	}
	if h, ok := isHTTPHeader(schema); ok {
		s.Encoder.SetHeader(h.Name).String(formatTimestamp(schema, "http-date", v))
	} else if q, ok := isHTTPQuery(schema); ok {
		s.Encoder.SetQuery(q.Name).String(formatTimestamp(schema, "date-time", v))
	} else {
		s.Body.WriteTime(schema, v)
	}
}

// WriteTimePtr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteTimePtr(schema *smithy.Schema, v *time.Time) {
	if v != nil {
		s.WriteTime(schema, *v)
	}
}

// WriteList implements [smithy.ShapeSerializer].
func (s *Serializer) WriteList(schema *smithy.Schema) {
	if h, ok := isHTTPHeader(schema); ok {
		s.listMode = listModeHeader
		s.listName = h.Name
		return
	}
	if q, ok := isHTTPQuery(schema); ok {
		s.listMode = listModeQuery
		s.listName = q.Name
		return
	}
	s.Body.WriteList(schema)
}

// CloseList implements [smithy.ShapeSerializer].
func (s *Serializer) CloseList() {
	if s.listMode != listModeNone {
		s.listMode = listModeNone
		s.listName = ""
		return
	}
	s.Body.CloseList()
}

// WriteMap implements [smithy.ShapeSerializer].
func (s *Serializer) WriteMap(schema *smithy.Schema) {
	if ph, ok := isHTTPPrefixHeaders(schema); ok {
		s.mapMode = mapModePrefixHeaders
		s.mapPrefix = ph.Prefix
		return
	}
	if isHTTPQueryParams(schema) {
		s.mapMode = mapModeQueryParams
		return
	}
	s.Body.WriteMap(schema)
}

// WriteKey implements [smithy.ShapeSerializer].
func (s *Serializer) WriteKey(schema *smithy.Schema, key string) {
	switch s.mapMode {
	case mapModePrefixHeaders, mapModeQueryParams:
		s.currentKey = key
	default:
		s.Body.WriteKey(schema, key)
	}
}

// CloseMap implements [smithy.ShapeSerializer].
func (s *Serializer) CloseMap() {
	if s.mapMode != mapModeNone {
		s.mapMode = mapModeNone
		s.mapPrefix = ""
		s.currentKey = ""
		return
	}
	s.Body.CloseMap()
}

// WriteStruct implements [smithy.ShapeSerializer].
func (s *Serializer) WriteStruct(schema *smithy.Schema, v smithy.Serializable) {
	if isHTTPPayload(schema) {
		// The entire struct is the payload body - serialize directly to body.
		s.Body.WriteStruct(schema, v)
		return
	}
	// For top-level input structs, the Serialize method on the struct will
	// call back into this serializer for each member, which will route
	// based on traits.
	if v != nil {
		v.Serialize(s)
	}
}

// WriteUnion implements [smithy.ShapeSerializer].
func (s *Serializer) WriteUnion(schema, variant *smithy.Schema, v smithy.Serializable) {
	s.Body.WriteUnion(schema, variant, v)
}

// WriteNil implements [smithy.ShapeSerializer].
func (s *Serializer) WriteNil(schema *smithy.Schema) {
	s.Body.WriteNil(schema)
}

// WriteBigInteger implements [smithy.ShapeSerializer].
func (s *Serializer) WriteBigInteger(schema *smithy.Schema, v big.Int) {
	s.Body.WriteBigInteger(schema, v)
}

// WriteBigDecimal implements [smithy.ShapeSerializer].
func (s *Serializer) WriteBigDecimal(schema *smithy.Schema, v big.Float) {
	s.Body.WriteBigDecimal(schema, v)
}

// WriteDocument implements [smithy.ShapeSerializer].
func (s *Serializer) WriteDocument(schema *smithy.Schema, v document.Value) {
	s.Body.WriteDocument(schema, v)
}

// FormatHeaderTimestamp formats a timestamp for use in an HTTP header.
func FormatHeaderTimestamp(v time.Time) string {
	return smithytime.FormatHTTPDate(v)
}

// FormatQueryTimestamp formats a timestamp for use in a query string.
func FormatQueryTimestamp(v time.Time) string {
	return smithytime.FormatDateTime(v)
}

// FormatLabelInt formats an integer for use in a URI label.
func FormatLabelInt(v int64) string {
	return strconv.FormatInt(v, 10)
}

// FormatLabelString is a passthrough for URI label strings.
func FormatLabelString(v string) string {
	return v
}

// FormatLabelBool formats a bool for use in a URI label.
func FormatLabelBool(v bool) string {
	return fmt.Sprintf("%t", v)
}
