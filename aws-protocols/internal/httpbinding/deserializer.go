package httpbinding

import (
	"encoding/base64"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/document"
	"github.com/aws/smithy-go/traits"
)

// Deserializer is a ShapeDeserializer that reads HTTP-bound output struct
// members from the response (headers, status code, prefix headers, payload)
// and delegates body members to the wrapped body deserializer.
type Deserializer struct {
	Response *http.Response
	Body     smithy.ShapeDeserializer
	Payload  []byte // raw response body bytes, for httpPayload blob/string

	// state for top-level struct member iteration
	httpMembers  []*smithy.Schema // queued HTTP-bound members
	httpIdx      int              // current index into httpMembers
	inHTTP       bool             // true when the current member is HTTP-bound
	bodyStarted  bool             // true once we've called Body.ReadStruct
	hasPayload   bool             // true if any member has httpPayload
	structSchema *smithy.Schema
	depth        int // nesting depth; only depth 1 gets HTTP binding treatment

	// state for httpPrefixHeaders map deserialization
	prefixMode    bool     // true when deserializing a prefix headers map
	prefixValue   string   // prefix to match
	prefixKeys    []string // collected header keys (without prefix)
	prefixKeyIdx  int

	// state for header list deserialization
	headerListMode   bool     // true when deserializing a header-bound list
	headerListValues []string // collected header values
	headerListIdx    int
}

var _ smithy.ShapeDeserializer = (*Deserializer)(nil)

func isHTTPBound(schema *smithy.Schema) bool {
	if _, ok := isHTTPHeader(schema); ok {
		return true
	}
	if _, ok := isHTTPPrefixHeaders(schema); ok {
		return true
	}
	if _, ok := smithy.SchemaTrait[*traits.HTTPResponseCode](schema); ok {
		return true
	}
	if _, ok := smithy.SchemaTrait[*traits.HTTPPayload](schema); ok {
		return true
	}
	return false
}

// ReadStruct implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadStruct(s *smithy.Schema) error {
	d.depth++
	if d.depth > 1 {
		return d.Body.ReadStruct(s)
	}

	// Top-level output struct: scan for HTTP-bound members.
	d.structSchema = s
	d.httpMembers = d.httpMembers[:0]
	d.httpIdx = 0
	d.inHTTP = false
	d.bodyStarted = false
	d.hasPayload = false

	for _, member := range s.Members() {
		if isHTTPBound(member) {
			d.httpMembers = append(d.httpMembers, member)
			if _, ok := smithy.SchemaTrait[*traits.HTTPPayload](member); ok {
				d.hasPayload = true
			}
		}
	}

	return nil
}

// ReadStructMember implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadStructMember() (*smithy.Schema, error) {
	if d.depth > 1 {
		ms, err := d.Body.ReadStructMember()
		if ms == nil {
			d.depth--
		}
		return ms, err
	}

	// Top-level: yield HTTP-bound members first.
	if d.httpIdx < len(d.httpMembers) {
		member := d.httpMembers[d.httpIdx]
		d.httpIdx++
		// Skip httpPayload struct members when the body is empty — the
		// caller would allocate the struct, but there's nothing to fill.
		if _, ok := smithy.SchemaTrait[*traits.HTTPPayload](member); ok && len(d.Payload) == 0 {
			return d.ReadStructMember()
		}
		d.inHTTP = true
		return member, nil
	}

	// If there's an httpPayload member, there are no unbound body members.
	if d.hasPayload {
		d.depth--
		return nil, nil
	}

	// Then delegate to body for unbound members.
	if !d.bodyStarted {
		d.bodyStarted = true
		if err := d.Body.ReadStruct(d.structSchema); err != nil {
			return nil, err
		}
	}
	d.inHTTP = false
	ms, err := d.Body.ReadStructMember()
	if ms == nil {
		d.depth--
	}
	return ms, err
}

// ReadString implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadString(s *smithy.Schema, v *string) error {
	if d.headerListMode {
		*v = d.headerListValues[d.headerListIdx]
		d.headerListIdx++
		return nil
	}
	if d.inHTTP {
		if h, ok := isHTTPHeader(s); ok {
			*v = d.Response.Header.Get(h.Name)
			return nil
		}
		if _, ok := smithy.SchemaTrait[*traits.HTTPPayload](s); ok {
			*v = string(d.Payload)
			return nil
		}
	}
	if d.prefixMode {
		key := d.prefixKeys[d.prefixKeyIdx-1]
		*v = d.Response.Header.Get(d.prefixValue + key)
		return nil
	}
	return d.Body.ReadString(s, v)
}

// ReadStringPtr implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadStringPtr(s *smithy.Schema, v **string) error {
	if d.headerListMode {
		sv := d.headerListValues[d.headerListIdx]
		d.headerListIdx++
		*v = &sv
		return nil
	}
	if d.inHTTP {
		if h, ok := isHTTPHeader(s); ok {
			if hv := d.Response.Header.Get(h.Name); hv != "" {
				*v = &hv
				return nil
			}
			return nil
		}
		if _, ok := smithy.SchemaTrait[*traits.HTTPPayload](s); ok {
			sv := string(d.Payload)
			*v = &sv
			return nil
		}
	}
	if d.prefixMode {
		key := d.prefixKeys[d.prefixKeyIdx-1]
		sv := d.Response.Header.Get(d.prefixValue + key)
		if sv != "" {
			*v = &sv
		}
		return nil
	}
	return d.Body.ReadStringPtr(s, v)
}

// ReadBool implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadBool(s *smithy.Schema, v *bool) error {
	if d.headerListMode {
		hv := d.headerListValues[d.headerListIdx]
		d.headerListIdx++
		b, err := strconv.ParseBool(hv)
		if err != nil {
			return fmt.Errorf("parse header list value %q as bool: %w", hv, err)
		}
		*v = b
		return nil
	}
	if d.inHTTP {
		if h, ok := isHTTPHeader(s); ok {
			hv := d.Response.Header.Get(h.Name)
			if hv == "" {
				return nil
			}
			b, err := strconv.ParseBool(hv)
			if err != nil {
				return fmt.Errorf("parse header %q as bool: %w", h.Name, err)
			}
			*v = b
			return nil
		}
	}
	return d.Body.ReadBool(s, v)
}

// ReadBoolPtr implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadBoolPtr(s *smithy.Schema, v **bool) error {
	if d.headerListMode {
		hv := d.headerListValues[d.headerListIdx]
		d.headerListIdx++
		b, err := strconv.ParseBool(hv)
		if err != nil {
			return fmt.Errorf("parse header list value %q as bool: %w", hv, err)
		}
		*v = &b
		return nil
	}
	if d.inHTTP {
		if h, ok := isHTTPHeader(s); ok {
			hv := d.Response.Header.Get(h.Name)
			if hv == "" {
				return nil
			}
			b, err := strconv.ParseBool(hv)
			if err != nil {
				return fmt.Errorf("parse header %q as bool: %w", h.Name, err)
			}
			*v = &b
			return nil
		}
	}
	return d.Body.ReadBoolPtr(s, v)
}

// ReadInt8 implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadInt8(s *smithy.Schema, v *int8) error {
	if d.headerListMode {
		return d.readHeaderListInt(func(n int64) { *v = int8(n) })
	}
	if d.inHTTP {
		return d.readHeaderInt(s, func(n int64) { *v = int8(n) })
	}
	return d.Body.ReadInt8(s, v)
}

// ReadInt8Ptr implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadInt8Ptr(s *smithy.Schema, v **int8) error {
	if d.headerListMode {
		return d.readHeaderListInt(func(n int64) { val := int8(n); *v = &val })
	}
	if d.inHTTP {
		return d.readHeaderInt(s, func(n int64) { val := int8(n); *v = &val })
	}
	return d.Body.ReadInt8Ptr(s, v)
}

// ReadInt16 implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadInt16(s *smithy.Schema, v *int16) error {
	if d.headerListMode {
		return d.readHeaderListInt(func(n int64) { *v = int16(n) })
	}
	if d.inHTTP {
		return d.readHeaderInt(s, func(n int64) { *v = int16(n) })
	}
	return d.Body.ReadInt16(s, v)
}

// ReadInt16Ptr implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadInt16Ptr(s *smithy.Schema, v **int16) error {
	if d.headerListMode {
		return d.readHeaderListInt(func(n int64) { val := int16(n); *v = &val })
	}
	if d.inHTTP {
		return d.readHeaderInt(s, func(n int64) { val := int16(n); *v = &val })
	}
	return d.Body.ReadInt16Ptr(s, v)
}

// ReadInt32 implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadInt32(s *smithy.Schema, v *int32) error {
	if d.headerListMode {
		return d.readHeaderListInt(func(n int64) { *v = int32(n) })
	}
	if d.inHTTP {
		if _, ok := smithy.SchemaTrait[*traits.HTTPResponseCode](s); ok {
			*v = int32(d.Response.StatusCode)
			return nil
		}
		return d.readHeaderInt(s, func(n int64) { *v = int32(n) })
	}
	return d.Body.ReadInt32(s, v)
}

// ReadInt32Ptr implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadInt32Ptr(s *smithy.Schema, v **int32) error {
	if d.headerListMode {
		return d.readHeaderListInt(func(n int64) { val := int32(n); *v = &val })
	}
	if d.inHTTP {
		if _, ok := smithy.SchemaTrait[*traits.HTTPResponseCode](s); ok {
			val := int32(d.Response.StatusCode)
			*v = &val
			return nil
		}
		return d.readHeaderInt(s, func(n int64) { val := int32(n); *v = &val })
	}
	return d.Body.ReadInt32Ptr(s, v)
}

// ReadInt64 implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadInt64(s *smithy.Schema, v *int64) error {
	if d.headerListMode {
		return d.readHeaderListInt(func(n int64) { *v = n })
	}
	if d.inHTTP {
		return d.readHeaderInt(s, func(n int64) { *v = n })
	}
	return d.Body.ReadInt64(s, v)
}

// ReadInt64Ptr implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadInt64Ptr(s *smithy.Schema, v **int64) error {
	if d.headerListMode {
		return d.readHeaderListInt(func(n int64) { *v = &n })
	}
	if d.inHTTP {
		return d.readHeaderInt(s, func(n int64) { *v = &n })
	}
	return d.Body.ReadInt64Ptr(s, v)
}

func (d *Deserializer) readHeaderInt(s *smithy.Schema, assign func(int64)) error {
	h, ok := isHTTPHeader(s)
	if !ok {
		return nil
	}
	hv := d.Response.Header.Get(h.Name)
	if hv == "" {
		return nil
	}
	n, err := strconv.ParseInt(hv, 10, 64)
	if err != nil {
		return fmt.Errorf("parse header %q as int: %w", h.Name, err)
	}
	assign(n)
	return nil
}

func (d *Deserializer) readHeaderListInt(assign func(int64)) error {
	hv := d.headerListValues[d.headerListIdx]
	d.headerListIdx++
	n, err := strconv.ParseInt(hv, 10, 64)
	if err != nil {
		return fmt.Errorf("parse header list value %q as int: %w", hv, err)
	}
	assign(n)
	return nil
}

// ReadFloat32 implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadFloat32(s *smithy.Schema, v *float32) error {
	if d.headerListMode {
		return d.readHeaderListFloat(func(n float64) { *v = float32(n) })
	}
	if d.inHTTP {
		return d.readHeaderFloat(s, func(n float64) { *v = float32(n) })
	}
	return d.Body.ReadFloat32(s, v)
}

// ReadFloat32Ptr implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadFloat32Ptr(s *smithy.Schema, v **float32) error {
	if d.headerListMode {
		return d.readHeaderListFloat(func(n float64) { val := float32(n); *v = &val })
	}
	if d.inHTTP {
		return d.readHeaderFloat(s, func(n float64) { val := float32(n); *v = &val })
	}
	return d.Body.ReadFloat32Ptr(s, v)
}

// ReadFloat64 implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadFloat64(s *smithy.Schema, v *float64) error {
	if d.headerListMode {
		return d.readHeaderListFloat(func(n float64) { *v = n })
	}
	if d.inHTTP {
		return d.readHeaderFloat(s, func(n float64) { *v = n })
	}
	return d.Body.ReadFloat64(s, v)
}

// ReadFloat64Ptr implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadFloat64Ptr(s *smithy.Schema, v **float64) error {
	if d.headerListMode {
		return d.readHeaderListFloat(func(n float64) { *v = &n })
	}
	if d.inHTTP {
		return d.readHeaderFloat(s, func(n float64) { *v = &n })
	}
	return d.Body.ReadFloat64Ptr(s, v)
}

func (d *Deserializer) readHeaderFloat(s *smithy.Schema, assign func(float64)) error {
	h, ok := isHTTPHeader(s)
	if !ok {
		return nil
	}
	hv := d.Response.Header.Get(h.Name)
	if hv == "" {
		return nil
	}
	n, err := strconv.ParseFloat(hv, 64)
	if err != nil {
		return fmt.Errorf("parse header %q as float: %w", h.Name, err)
	}
	assign(n)
	return nil
}

func (d *Deserializer) readHeaderListFloat(assign func(float64)) error {
	hv := d.headerListValues[d.headerListIdx]
	d.headerListIdx++
	n, err := strconv.ParseFloat(hv, 64)
	if err != nil {
		return fmt.Errorf("parse header list value %q as float: %w", hv, err)
	}
	assign(n)
	return nil
}

// ReadTime implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadTime(s *smithy.Schema, v *time.Time) error {
	if d.headerListMode {
		hv := d.headerListValues[d.headerListIdx]
		d.headerListIdx++
		t, err := parseTimestamp(s, "http-date", hv)
		if err != nil {
			return fmt.Errorf("parse header list value %q as time: %w", hv, err)
		}
		*v = t
		return nil
	}
	if d.inHTTP {
		if h, ok := isHTTPHeader(s); ok {
			hv := d.Response.Header.Get(h.Name)
			if hv == "" {
				return nil
			}
			t, err := parseTimestamp(s, "http-date", hv)
			if err != nil {
				return fmt.Errorf("parse header %q as time: %w", h.Name, err)
			}
			*v = t
			return nil
		}
	}
	return d.Body.ReadTime(s, v)
}

// ReadTimePtr implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadTimePtr(s *smithy.Schema, v **time.Time) error {
	if d.headerListMode {
		hv := d.headerListValues[d.headerListIdx]
		d.headerListIdx++
		t, err := parseTimestamp(s, "http-date", hv)
		if err != nil {
			return fmt.Errorf("parse header list value %q as time: %w", hv, err)
		}
		*v = &t
		return nil
	}
	if d.inHTTP {
		if h, ok := isHTTPHeader(s); ok {
			hv := d.Response.Header.Get(h.Name)
			if hv == "" {
				return nil
			}
			t, err := parseTimestamp(s, "http-date", hv)
			if err != nil {
				return fmt.Errorf("parse header %q as time: %w", h.Name, err)
			}
			*v = &t
			return nil
		}
	}
	return d.Body.ReadTimePtr(s, v)
}

// ReadBlob implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadBlob(s *smithy.Schema, v *[]byte) error {
	if d.inHTTP {
		if _, ok := smithy.SchemaTrait[*traits.HTTPPayload](s); ok {
			*v = d.Payload
			return nil
		}
		if h, ok := isHTTPHeader(s); ok {
			hv := d.Response.Header.Get(h.Name)
			if hv == "" {
				return nil
			}
			b, err := base64.StdEncoding.DecodeString(hv)
			if err != nil {
				return fmt.Errorf("decode header %q as base64 blob: %w", h.Name, err)
			}
			*v = b
			return nil
		}
	}
	return d.Body.ReadBlob(s, v)
}

// ReadList implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadList(s *smithy.Schema) error {
	if d.inHTTP {
		if h, ok := isHTTPHeader(s); ok {
			d.headerListMode = true
			d.headerListValues = splitHeaderListValues(d.Response.Header, h.Name)
			d.headerListIdx = 0
			return nil
		}
	}
	return d.Body.ReadList(s)
}

// ReadListItem implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadListItem(s *smithy.Schema) (bool, error) {
	if d.headerListMode {
		if d.headerListIdx >= len(d.headerListValues) {
			d.headerListMode = false
			return false, nil
		}
		return true, nil
	}
	return d.Body.ReadListItem(s)
}

// ReadMap implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadMap(s *smithy.Schema) error {
	if d.inHTTP {
		if ph, ok := isHTTPPrefixHeaders(s); ok {
			d.prefixMode = true
			d.prefixValue = ph.Prefix
			d.prefixKeys = d.prefixKeys[:0]
			d.prefixKeyIdx = 0
			// Collect all response headers matching the prefix.
			// Header keys in Go are canonical (e.g. "X-Foo-Bar"), so we
			// canonicalize the prefix for comparison and strip it to get
			// the map key.
			canonical := http.CanonicalHeaderKey(ph.Prefix)
			for name := range d.Response.Header {
				if len(name) > len(canonical) && strings.EqualFold(name[:len(canonical)], canonical) {
					d.prefixKeys = append(d.prefixKeys, strings.ToLower(name[len(canonical):]))
				}
			}
			return nil
		}
	}
	return d.Body.ReadMap(s)
}

// ReadMapKey implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadMapKey(s *smithy.Schema) (string, bool, error) {
	if d.prefixMode {
		if d.prefixKeyIdx >= len(d.prefixKeys) {
			d.prefixMode = false
			return "", false, nil
		}
		key := d.prefixKeys[d.prefixKeyIdx]
		d.prefixKeyIdx++
		return key, true, nil
	}
	return d.Body.ReadMapKey(s)
}

// ReadNil implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadNil(s *smithy.Schema) (bool, error) {
	if d.inHTTP {
		return false, nil
	}
	return d.Body.ReadNil(s)
}

// ReadDocument implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadDocument(s *smithy.Schema, v *document.Value) error {
	return d.Body.ReadDocument(s, v)
}

// ReadUnion implements [smithy.ShapeDeserializer].
func (d *Deserializer) ReadUnion(s *smithy.Schema) (*smithy.Schema, error) {
	return d.Body.ReadUnion(s)
}

// splitHeaderListValues splits a comma-separated header value into individual
// trimmed values. For timestamp lists (http-date format), it handles the
// commas within date values by attempting to reassemble split parts.
func splitHeaderListValues(header http.Header, name string) []string {
	values := header.Values(name)
	if len(values) == 0 {
		return nil
	}
	var result []string
	for _, v := range values {
		for _, part := range strings.Split(v, ",") {
			if trimmed := strings.TrimSpace(part); trimmed != "" {
				result = append(result, trimmed)
			}
		}
	}
	return result
}

// splitHeaderTimestampList splits a comma-separated header value containing
// HTTP-date timestamps. HTTP-date values contain commas (e.g.
// "Mon, 16 Dec 2019 23:48:18 GMT") so naive comma splitting doesn't work.
// Instead, split on ", " and reassemble adjacent parts that form valid dates.
func splitHeaderTimestampList(header http.Header, name string) []string {
	values := header.Values(name)
	if len(values) == 0 {
		return nil
	}
	var result []string
	for _, v := range values {
		parts := strings.Split(v, ",")
		var pending string
		for i, part := range parts {
			trimmed := strings.TrimSpace(part)
			if trimmed == "" {
				continue
			}
			if pending == "" {
				pending = trimmed
			} else {
				pending += ", " + trimmed
			}
			// Try to parse as a timestamp. If it works, emit it.
			if _, err := parseTimestamp(nil, "http-date", pending); err == nil {
				result = append(result, pending)
				pending = ""
			} else if i == len(parts)-1 {
				// Last part: emit whatever we have.
				result = append(result, pending)
				pending = ""
			}
		}
	}
	return result
}
