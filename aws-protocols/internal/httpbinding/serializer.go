// Package httpbinding implements a ShapeSerializer that routes struct members
// to HTTP binding locations (header, query, URI label, payload) based on
// their schema traits.
package httpbinding

import (
	"encoding/base64"
	"fmt"
	"math/big"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/aws/smithy-go"
	awsjson "github.com/aws/smithy-go/aws-protocols/internal/json"
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

	// StreamingPayloadContentType is the content type for a streaming payload,
	// resolved from the @mediaType trait on the streaming blob member.
	StreamingPayloadContentType string

	opts serializerOptions

	// state for httpPrefixHeaders / httpQueryParams map serialization
	mapMode    mapBindingMode
	mapPrefix  string // prefix for httpPrefixHeaders
	currentKey string // current map key

	// state for list serialization in HTTP bindings
	listMode     listBindingMode
	listName     string // header or query param name for list binding
	listHasItems bool   // whether any items were written in current list

	// noBody is true when the top-level struct has no body-bound members,
	// meaning the body object open/close should be suppressed.
	noBody bool

	// HasStructPayload is true when the input has an httpPayload member
	// targeting a struct type. When set, the protocol should send an empty
	// JSON body ({}) even if no payload content was serialized.
	HasStructPayload bool
}

type serializerOptions struct {
	writeZeroValues bool
}

func (s *Serializer) withWriteZero(fn func()) {
	prev := s.opts.writeZeroValues
	s.opts.writeZeroValues = true
	fn()
	s.opts.writeZeroValues = prev
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

// quoteHeaderValue quotes a string for use in an HTTP header list value if it
// contains commas or double quotes, per RFC 7230.
func quoteHeaderValue(v string) string {
	if !strings.ContainsAny(v, ",\"") {
		return v
	}
	var b strings.Builder
	b.WriteByte('"')
	for i := 0; i < len(v); i++ {
		if v[i] == '"' || v[i] == '\\' {
			b.WriteByte('\\')
		}
		b.WriteByte(v[i])
	}
	b.WriteByte('"')
	return b.String()
}

// Bytes returns the serialized body bytes.
func (s *Serializer) Bytes() []byte {
	return s.Body.Bytes()
}

func isHTTPHeader(schema *smithy.Schema) (*traits.HTTPHeader, bool) {
	h, ok := smithy.SchemaTrait[*traits.HTTPHeader](schema)
	if ok {
		h.Name = http.CanonicalHeaderKey(h.Name)
	}
	return h, ok
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
	ph, ok := smithy.SchemaTrait[*traits.HTTPPrefixHeaders](schema)
	if ok {
		ph.Prefix = http.CanonicalHeaderKey(ph.Prefix)
	}
	return ph, ok
}

func isHTTPQueryParams(schema *smithy.Schema) bool {
	_, ok := smithy.SchemaTrait[*traits.HTTPQueryParams](schema)
	return ok
}

// hasBodyMembers reports whether any member of the schema is bound to the
// HTTP body (i.e. has no HTTP binding trait).
func hasBodyMembers(schema *smithy.Schema) bool {
	for _, member := range schema.Members() {
		if !isSerializerHTTPBound(member) {
			return true
		}
	}
	return false
}

// isSerializerHTTPBound reports whether a member schema has an HTTP binding
// trait that routes it away from the body during serialization.
func isSerializerHTTPBound(schema *smithy.Schema) bool {
	if _, ok := isHTTPHeader(schema); ok {
		return true
	}
	if _, ok := isHTTPPrefixHeaders(schema); ok {
		return true
	}
	if isHTTPLabel(schema) {
		return true
	}
	if _, ok := isHTTPQuery(schema); ok {
		return true
	}
	if isHTTPQueryParams(schema) {
		return true
	}
	if isHTTPPayload(schema) {
		return true
	}
	return false
}

// WriteString implements [smithy.ShapeSerializer].
func (s *Serializer) WriteString(schema *smithy.Schema, v string) {
	if !s.opts.writeZeroValues && v == "" && s.mapMode == mapModeNone && s.listMode == listModeNone {
		return
	}
	switch s.mapMode {
	case mapModePrefixHeaders:
		s.Encoder.SetHeader(http.CanonicalHeaderKey(s.mapPrefix + s.currentKey)).String(v)
		return
	case mapModeQueryParams:
		s.Encoder.AddQuery(s.currentKey).String(v)
		return
	}
	switch s.listMode {
	case listModeHeader:
		s.Encoder.AddHeader(s.listName).String(quoteHeaderValue(v))
		s.listHasItems = true
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
		if _, ok := smithy.SchemaTrait[*traits.MediaType](schema); ok {
			s.Encoder.SetHeader(h.Name).String(base64.StdEncoding.EncodeToString([]byte(v)))
		} else {
			s.Encoder.SetHeader(h.Name).String(v)
		}
	} else if isHTTPLabel(schema) {
		s.Encoder.SetURI(schema.MemberName()).String(v)
	} else if q, ok := isHTTPQuery(schema); ok {
		s.Encoder.SetQuery(q.Name).String(v)
	} else if s.opts.writeZeroValues {
		s.Body.WriteStringPtr(schema, &v)
	} else {
		s.Body.WriteString(schema, v)
	}
}

// WriteStringPtr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteStringPtr(schema *smithy.Schema, v *string) {
	if v != nil {
		s.withWriteZero(func() { s.WriteString(schema, *v) })
	}
}

// WriteBool implements [smithy.ShapeSerializer].
func (s *Serializer) WriteBool(schema *smithy.Schema, v bool) {
	if s.listMode == listModeHeader {
		s.Encoder.AddHeader(s.listName).Boolean(v)
		s.listHasItems = true
		return
	}
	if s.listMode == listModeQuery {
		s.Encoder.AddQuery(s.listName).Boolean(v)
		return
	}
	if h, ok := isHTTPHeader(schema); ok {
		s.Encoder.SetHeader(h.Name).Boolean(v)
	} else if isHTTPLabel(schema) {
		s.Encoder.SetURI(schema.MemberName()).Boolean(v)
	} else if q, ok := isHTTPQuery(schema); ok {
		s.Encoder.SetQuery(q.Name).Boolean(v)
	} else if s.opts.writeZeroValues {
		s.Body.WriteBoolPtr(schema, &v)
	} else {
		s.Body.WriteBool(schema, v)
	}
}

// WriteBoolPtr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteBoolPtr(schema *smithy.Schema, v *bool) {
	if v != nil {
		s.withWriteZero(func() { s.WriteBool(schema, *v) })
	}
}

// WriteInt8 implements [smithy.ShapeSerializer].
func (s *Serializer) WriteInt8(schema *smithy.Schema, v int8) {
	if s.listMode == listModeHeader {
		s.Encoder.AddHeader(s.listName).Byte(v)
		s.listHasItems = true
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
	} else if s.opts.writeZeroValues {
		s.Body.WriteInt8Ptr(schema, &v)
	} else {
		s.Body.WriteInt8(schema, v)
	}
}

// WriteInt8Ptr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteInt8Ptr(schema *smithy.Schema, v *int8) {
	if v != nil {
		s.withWriteZero(func() { s.WriteInt8(schema, *v) })
	}
}

// WriteInt16 implements [smithy.ShapeSerializer].
func (s *Serializer) WriteInt16(schema *smithy.Schema, v int16) {
	if s.listMode == listModeHeader {
		s.Encoder.AddHeader(s.listName).Short(v)
		s.listHasItems = true
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
	} else if s.opts.writeZeroValues {
		s.Body.WriteInt16Ptr(schema, &v)
	} else {
		s.Body.WriteInt16(schema, v)
	}
}

// WriteInt16Ptr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteInt16Ptr(schema *smithy.Schema, v *int16) {
	if v != nil {
		s.withWriteZero(func() { s.WriteInt16(schema, *v) })
	}
}

// WriteInt32 implements [smithy.ShapeSerializer].
func (s *Serializer) WriteInt32(schema *smithy.Schema, v int32) {
	if s.listMode == listModeHeader {
		s.Encoder.AddHeader(s.listName).Integer(v)
		s.listHasItems = true
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
	} else if s.opts.writeZeroValues {
		s.Body.WriteInt32Ptr(schema, &v)
	} else {
		s.Body.WriteInt32(schema, v)
	}
}

// WriteInt32Ptr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteInt32Ptr(schema *smithy.Schema, v *int32) {
	if v != nil {
		s.withWriteZero(func() { s.WriteInt32(schema, *v) })
	}
}

// WriteInt64 implements [smithy.ShapeSerializer].
func (s *Serializer) WriteInt64(schema *smithy.Schema, v int64) {
	if s.listMode == listModeHeader {
		s.Encoder.AddHeader(s.listName).Long(v)
		s.listHasItems = true
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
	} else if s.opts.writeZeroValues {
		s.Body.WriteInt64Ptr(schema, &v)
	} else {
		s.Body.WriteInt64(schema, v)
	}
}

// WriteInt64Ptr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteInt64Ptr(schema *smithy.Schema, v *int64) {
	if v != nil {
		s.withWriteZero(func() { s.WriteInt64(schema, *v) })
	}
}

// WriteFloat32 implements [smithy.ShapeSerializer].
func (s *Serializer) WriteFloat32(schema *smithy.Schema, v float32) {
	if s.listMode == listModeHeader {
		s.Encoder.AddHeader(s.listName).Float(v)
		s.listHasItems = true
		return
	}
	if s.listMode == listModeQuery {
		s.Encoder.AddQuery(s.listName).Float(v)
		return
	}
	if h, ok := isHTTPHeader(schema); ok {
		s.Encoder.SetHeader(h.Name).Float(v)
	} else if isHTTPLabel(schema) {
		s.Encoder.SetURI(schema.MemberName()).Float(v)
	} else if q, ok := isHTTPQuery(schema); ok {
		s.Encoder.SetQuery(q.Name).Float(v)
	} else if s.opts.writeZeroValues {
		s.Body.WriteFloat32Ptr(schema, &v)
	} else {
		s.Body.WriteFloat32(schema, v)
	}
}

// WriteFloat32Ptr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteFloat32Ptr(schema *smithy.Schema, v *float32) {
	if v != nil {
		s.withWriteZero(func() { s.WriteFloat32(schema, *v) })
	}
}

// WriteFloat64 implements [smithy.ShapeSerializer].
func (s *Serializer) WriteFloat64(schema *smithy.Schema, v float64) {
	if s.listMode == listModeHeader {
		s.Encoder.AddHeader(s.listName).Double(v)
		s.listHasItems = true
		return
	}
	if s.listMode == listModeQuery {
		s.Encoder.AddQuery(s.listName).Double(v)
		return
	}
	if h, ok := isHTTPHeader(schema); ok {
		s.Encoder.SetHeader(h.Name).Double(v)
	} else if isHTTPLabel(schema) {
		s.Encoder.SetURI(schema.MemberName()).Double(v)
	} else if q, ok := isHTTPQuery(schema); ok {
		s.Encoder.SetQuery(q.Name).Double(v)
	} else if s.opts.writeZeroValues {
		s.Body.WriteFloat64Ptr(schema, &v)
	} else {
		s.Body.WriteFloat64(schema, v)
	}
}

// WriteFloat64Ptr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteFloat64Ptr(schema *smithy.Schema, v *float64) {
	if v != nil {
		s.withWriteZero(func() { s.WriteFloat64(schema, *v) })
	}
}

// WriteBlob implements [smithy.ShapeSerializer].
func (s *Serializer) WriteBlob(schema *smithy.Schema, v []byte) {
	if isHTTPPayload(schema) {
		s.PayloadBytes = v
		if mt, ok := smithy.SchemaTrait[*traits.MediaType](schema); ok {
			s.PayloadContentType = mt.Type
		} else {
			s.PayloadContentType = "application/octet-stream"
		}
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
		s.listHasItems = true
		return
	}
	if s.listMode == listModeQuery {
		s.Encoder.AddQuery(s.listName).String(formatTimestamp(schema, "date-time", v))
		return
	}
	if h, ok := isHTTPHeader(schema); ok {
		s.Encoder.SetHeader(h.Name).String(formatTimestamp(schema, "http-date", v))
	} else if isHTTPLabel(schema) {
		s.Encoder.SetURI(schema.MemberName()).String(formatTimestamp(schema, "date-time", v))
	} else if q, ok := isHTTPQuery(schema); ok {
		s.Encoder.SetQuery(q.Name).String(formatTimestamp(schema, "date-time", v))
	} else {
		s.Body.WriteTime(schema, v)
	}
}

// WriteTimePtr implements [smithy.ShapeSerializer].
func (s *Serializer) WriteTimePtr(schema *smithy.Schema, v *time.Time) {
	if v != nil {
		s.withWriteZero(func() { s.WriteTime(schema, *v) })
	}
}

// WriteList implements [smithy.ShapeSerializer].
func (s *Serializer) WriteList(schema *smithy.Schema) {
	if s.mapMode == mapModeQueryParams {
		s.listMode = listModeQuery
		s.listName = s.currentKey
		return
	}
	if h, ok := isHTTPHeader(schema); ok {
		s.listMode = listModeHeader
		s.listName = h.Name
		s.listHasItems = false
		return
	}
	if q, ok := isHTTPQuery(schema); ok {
		s.listMode = listModeQuery
		s.listName = q.Name
		s.listHasItems = false
		return
	}
	s.Body.WriteList(schema)
}

// CloseList implements [smithy.ShapeSerializer].
func (s *Serializer) CloseList() {
	if s.listMode != listModeNone {
		if !s.listHasItems && s.listMode == listModeHeader {
			s.Encoder.SetHeader(s.listName).String("")
		}
		s.listMode = listModeNone
		s.listName = ""
		s.listHasItems = false
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
	// Detect streaming/struct payload from schema members.
	if schema != nil {
		for _, m := range schema.Members() {
			if isHTTPPayload(m) {
				if _, ok := smithy.SchemaTrait[*traits.Streaming](m); ok {
					s.StreamingPayloadContentType = "application/octet-stream"
					if mt, ok := smithy.SchemaTrait[*traits.MediaType](m); ok {
						s.StreamingPayloadContentType = mt.Type
					}
				} else if m.Type() == smithy.ShapeTypeStructure {
					s.HasStructPayload = true
				}
			}
		}
	}
	if schema != nil && !hasBodyMembers(schema) {
		s.noBody = true
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
	if s.noBody {
		s.noBody = false
		return
	}
	s.Body.CloseMap()
}

// WriteStruct implements [smithy.ShapeSerializer].
func (s *Serializer) WriteStruct(schema *smithy.Schema, v smithy.Serializable) {
	s.Body.WriteStruct(schema, v)
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
	if isHTTPPayload(schema) {
		// httpPayload document: serialize to raw bytes for the body.
		doc := awsjson.NewShapeSerializer()
		doc.WriteDocument(schema, v)
		s.PayloadBytes = doc.Bytes()
		s.PayloadContentType = "application/json"
		return
	}
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
