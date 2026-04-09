package json

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math"
	"strings"
	"time"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/document"
	smithytime "github.com/aws/smithy-go/time"
	"github.com/aws/smithy-go/traits"
)

// ShapeDeserializer implements unmarshaling of JSON into Smithy shapes.
type ShapeDeserializer struct {
	dec  *json.Decoder
	head stack
	opts ShapeDeserializerOptions

	// json.Decoder does not have a Peek() but we need to be able to
	// "lookahead" for conditionally pulling a null token out in ReadNil.
	peeked  json.Token
	hasPeek bool
}

// ShapeDeserializerOptions configures ShapeDeserializer.
type ShapeDeserializerOptions struct {
	// UseJSONName controls whether the @jsonName trait is used to
	// match JSON object keys to struct members. If false (the default),
	// only the member name is used. Protocols like restJson1 set this
	// to true, while RPC protocols like awsJson1_0 leave it false.
	UseJSONName bool
}

// NewShapeDeserializer creates a new ShapeDeserializer.
func NewShapeDeserializer(p []byte, opts ...func(*ShapeDeserializerOptions)) *ShapeDeserializer {
	o := ShapeDeserializerOptions{}
	for _, fn := range opts {
		fn(&o)
	}
	dec := json.NewDecoder(bytes.NewReader(p))
	dec.UseNumber()
	return &ShapeDeserializer{dec: dec, opts: o}
}

var _ smithy.ShapeDeserializer = (*ShapeDeserializer)(nil)

func (d *ShapeDeserializer) token() (json.Token, error) {
	if d.hasPeek {
		d.hasPeek = false
		return d.peeked, nil
	}
	return d.dec.Token()
}

func (d *ShapeDeserializer) more() bool {
	if d.hasPeek {
		return true
	}
	return d.dec.More()
}

func (d *ShapeDeserializer) expectDelim(e json.Delim) error {
	tok, err := d.token()
	if err != nil {
		return err
	}

	if a, ok := tok.(json.Delim); ok {
		if e != a {
			return fmt.Errorf("expect %s, got %s", e, a)
		}
		return nil
	}

	return fmt.Errorf("expect delim, got %T", tok)
}

// ReadNil implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadNil(s *smithy.Schema) (bool, error) {
	tok, err := d.token()
	if err != nil {
		return false, err
	}
	if tok == nil {
		return true, nil
	}

	// The only way to "unread" it is to note it and have token() return it
	// next time.
	d.peeked = tok
	d.hasPeek = true
	return false, nil
}

// ReadInt8 implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadInt8(s *smithy.Schema, v *int8) error {
	n, err := d.readInt(math.MinInt8, math.MaxInt8)
	*v = int8(n)
	return err
}

// ReadInt16 implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadInt16(s *smithy.Schema, v *int16) error {
	n, err := d.readInt(math.MinInt16, math.MaxInt16)
	*v = int16(n)
	return err
}

// ReadInt32 implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadInt32(s *smithy.Schema, v *int32) error {
	n, err := d.readInt(math.MinInt32, math.MaxInt32)
	*v = int32(n)
	return err
}

// ReadInt64 implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadInt64(s *smithy.Schema, v *int64) error {
	n, err := d.readInt(math.MinInt64, math.MaxInt64)
	*v = n
	return err
}

// ReadInt8Ptr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadInt8Ptr(s *smithy.Schema, v **int8) error {
	return readPtr(d, s, v, d.ReadInt8)
}

// ReadInt16Ptr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadInt16Ptr(s *smithy.Schema, v **int16) error {
	return readPtr(d, s, v, d.ReadInt16)
}

// ReadInt32Ptr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadInt32Ptr(s *smithy.Schema, v **int32) error {
	return readPtr(d, s, v, d.ReadInt32)
}

// ReadInt64Ptr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadInt64Ptr(s *smithy.Schema, v **int64) error {
	return readPtr(d, s, v, d.ReadInt64)
}

func (d *ShapeDeserializer) readInt(min, max int64) (int64, error) {
	tok, err := d.token()
	if err != nil {
		return 0, err
	}

	num, ok := tok.(json.Number)
	if !ok {
		return 0, fmt.Errorf("expected number, got %T", tok)
	}

	n, err := num.Int64()
	if err != nil {
		return 0, err
	}

	if n < min || n > max {
		return 0, fmt.Errorf("int %d exceeds range [%d, %d]", n, min, max)
	}

	return n, nil
}

// ReadFloat32 implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadFloat32(s *smithy.Schema, v *float32) error {
	n, err := d.readFloat()
	*v = float32(n)
	return err
}

// ReadFloat64 implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadFloat64(s *smithy.Schema, v *float64) error {
	n, err := d.readFloat()
	*v = n
	return err
}

// ReadFloat32Ptr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadFloat32Ptr(s *smithy.Schema, v **float32) error {
	return readPtr(d, s, v, d.ReadFloat32)
}

// ReadFloat64Ptr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadFloat64Ptr(s *smithy.Schema, v **float64) error {
	return readPtr(d, s, v, d.ReadFloat64)
}

func (d *ShapeDeserializer) readFloat() (float64, error) {
	tok, err := d.token()
	if err != nil {
		return 0, err
	}

	switch v := tok.(type) {
	case json.Number:
		return v.Float64()
	case string:
		switch {
		case strings.EqualFold(v, "NaN"):
			return math.NaN(), nil
		case strings.EqualFold(v, "Infinity"):
			return math.Inf(1), nil
		case strings.EqualFold(v, "-Infinity"):
			return math.Inf(-1), nil
		default:
			return 0, fmt.Errorf("unexpected string value for float: %s", v)
		}
	default:
		return 0, fmt.Errorf("expected number, got %T", tok)
	}
}

// ReadBool implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadBool(s *smithy.Schema, v *bool) error {
	tok, err := d.token()
	if err != nil {
		return err
	}

	b, ok := tok.(bool)
	if !ok {
		return fmt.Errorf("expected bool, got %T", tok)
	}

	*v = b
	return nil
}

// ReadBoolPtr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadBoolPtr(s *smithy.Schema, v **bool) error {
	return readPtr(d, s, v, d.ReadBool)
}

// ReadString implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadString(s *smithy.Schema, v *string) error {
	tok, err := d.token()
	if err != nil {
		return err
	}
	if tok == nil {
		return nil
	}

	str, ok := tok.(string)
	if !ok {
		return fmt.Errorf("expected string, got %T", tok)
	}

	*v = str
	return nil
}

// ReadStringPtr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadStringPtr(s *smithy.Schema, v **string) error {
	return readPtr(d, s, v, d.ReadString)
}

// ReadTime implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadTime(schema *smithy.Schema, v *time.Time) error {
	format := "epoch-seconds"
	if t, ok := smithy.SchemaTrait[*traits.TimestampFormat](schema); ok {
		format = t.Format
	}

	switch format {
	case "epoch-seconds":
		n, err := d.readFloat()
		if err != nil {
			return err
		}
		*v = smithytime.ParseEpochSeconds(n)
		return nil
	case "date-time":
		var s string
		if err := d.ReadString(schema, &s); err != nil {
			return err
		}
		t, err := smithytime.ParseDateTime(s)
		if err != nil {
			return err
		}
		*v = t
		return nil
	case "http-date":
		var s string
		if err := d.ReadString(schema, &s); err != nil {
			return err
		}
		t, err := smithytime.ParseHTTPDate(s)
		if err != nil {
			return err
		}
		*v = t
		return nil
	default:
		return fmt.Errorf("unknown timestamp format: %s", format)
	}
}

// ReadTimePtr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadTimePtr(schema *smithy.Schema, v **time.Time) error {
	return readPtr(d, schema, v, d.ReadTime)
}

// ReadBlob implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadBlob(s *smithy.Schema, v *[]byte) error {
	if isNil, err := d.ReadNil(s); isNil || err != nil {
		return err
	}

	tok, err := d.token()
	if err != nil {
		return err
	}

	str, ok := tok.(string)
	if !ok {
		return fmt.Errorf("expected string, got %T", tok)
	}

	b, err := base64.StdEncoding.DecodeString(str)
	if err != nil {
		return fmt.Errorf("decode base64 blob: %w", err)
	}

	*v = b
	return nil
}

// ReadList implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadList(s *smithy.Schema) error {
	tok, err := d.token()
	if err != nil {
		return err
	}

	delim, ok := tok.(json.Delim)
	if !ok || delim != '[' {
		return fmt.Errorf("expected '[', got %v", tok)
	}

	return nil
}

// ReadListItem implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadListItem(s *smithy.Schema) (bool, error) {
	if !d.more() {
		return false, d.expectDelim(']')
	}

	return true, nil
}

// ReadMap implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadMap(s *smithy.Schema) error {
	tok, err := d.token()
	if err != nil {
		return err
	}

	delim, ok := tok.(json.Delim)
	if !ok || delim != '{' {
		return fmt.Errorf("expected '{', got %v", tok)
	}

	return nil
}

// ReadMapKey implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadMapKey(s *smithy.Schema) (string, bool, error) {
	if !d.more() {
		return "", false, d.expectDelim('}')
	}

	tok, err := d.token()
	if err != nil {
		return "", false, err
	}

	key, ok := tok.(string)
	if !ok {
		return "", false, fmt.Errorf("expected string key, got %T", tok)
	}

	return key, true, nil
}

// ReadStruct implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadStruct(s *smithy.Schema) error {
	if isNil, err := d.ReadNil(s); isNil || err != nil {
		return err
	}

	tok, err := d.token()
	if err != nil {
		return err
	}

	delim, ok := tok.(json.Delim)
	if !ok || delim != '{' {
		return fmt.Errorf("expected '{', got %v", tok)
	}

	d.head.Push(s)
	return nil
}

// ReadStructMember implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadStructMember() (*smithy.Schema, error) {
	if !d.more() {
		d.head.Pop()
		return nil, d.expectDelim('}')
	}

	tok, err := d.token()
	if err != nil {
		return nil, err
	}

	key, ok := tok.(string)
	if !ok {
		return nil, fmt.Errorf("expected string key, got %T", tok)
	}

	schema, ok := d.head.Top().(*smithy.Schema)
	if !ok {
		return nil, fmt.Errorf("ReadStructMember called without ReadStruct?")
	}

	member := schema.Member(key)
	if member == nil && d.opts.UseJSONName {
		for _, m := range schema.Members() {
			if jn, ok := smithy.SchemaTrait[*traits.JSONName](m); ok && jn.Name == key {
				member = m
				break
			}
		}
	}
	if member == nil {
		if err := d.skip(); err != nil {
			return nil, err
		}
		return d.ReadStructMember() // just try the next one
	}

	return member, nil
}

type unionCtx struct {
	schema *smithy.Schema
}

// ReadUnion implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadUnion(s *smithy.Schema) (*smithy.Schema, error) {
	if _, ok := d.head.Top().(*unionCtx); !ok {
		if isNil, err := d.ReadNil(s); isNil || err != nil {
			return nil, err
		}

		tok, err := d.token()
		if err != nil {
			return nil, err
		}
		delim, ok := tok.(json.Delim)
		if !ok || delim != '{' {
			return nil, fmt.Errorf("expected '{', got %v", tok)
		}
		d.head.Push(&unionCtx{schema: s})
	}

	for d.more() {
		tok, err := d.token()
		if err != nil {
			return nil, err
		}
		key, ok := tok.(string)
		if !ok {
			return nil, fmt.Errorf("expected string key, got %T", tok)
		}

		// skip null values
		isNil, err := d.ReadNil(nil)
		if err != nil {
		    return nil, err
		} else {
		    continue
		}
			if err != nil {
				return nil, err
			}
			continue
		}

		member := s.Member(key)
		if member == nil && d.opts.UseJSONName {
			for _, m := range s.Members() {
				if jn, ok := smithy.SchemaTrait[*traits.JSONName](m); ok && jn.Name == key {
					member = m
					break
				}
			}
		}
		if member == nil {
			if err := d.skip(); err != nil {
				return nil, err
			}
			continue
		}

		return member, nil
	}

	d.head.Pop()
	return nil, d.expectDelim('}')
}

// used to skip over a struct member that we didn't have a schema for, though
// it also calls itself
func (d *ShapeDeserializer) skip() error {
	tok, err := d.token()
	if err != nil {
		return err
	}

	switch v := tok.(type) {
	case json.Delim:
		switch v {
		case '{':
			for d.more() {
				if _, err := d.token(); err != nil { // the key
					return err
				}
				if err := d.skip(); err != nil { // the value
					return err
				}
			}
			_, err := d.token() // the '}'
			return err
		case '[':
			for d.more() {
				if err := d.skip(); err != nil {
					return err
				}
			}
			_, err := d.token() // the ']'
			return err
		default:
			return fmt.Errorf("unexpected delimiter: %v", v)
		}
	default:
		return nil // scalar, don't have to do anything else
	}
}

// ReadDocument reads a JSON value into a document Value.
//
// For now this produces an [document.Opaque] wrapping the raw decoded value,
// which is what the legacy document bridge in generated code expects. The
// jsonToValue conversion is available for future use when the new typed
// document path is wired up end-to-end.
func (d *ShapeDeserializer) ReadDocument(schema *smithy.Schema, v *document.Value) error {
	var raw any
	if err := d.dec.Decode(&raw); err != nil {
		return err
	}

	*v = document.Opaque{Value: raw}
	return nil
}

func readPtr[T any](d *ShapeDeserializer, s *smithy.Schema, v **T, read func(*smithy.Schema, *T) error) error {
	if isNil, err := d.ReadNil(s); isNil || err != nil {
		return err
	}

	if *v == nil {
		*v = new(T)
	}
	return read(s, *v)
}

func jsonToValue(v any) (document.Value, error) {
	switch vv := v.(type) {
	case nil:
		return document.Null{}, nil
	case bool:
		return document.Boolean(vv), nil
	case json.Number:
		return document.Number(vv.String()), nil
	case float64:
		return document.Number(fmt.Sprintf("%v", vv)), nil
	case string:
		return document.String(vv), nil
	case []any:
		list := make(document.List, len(vv))
		for i, item := range vv {
			dv, err := jsonToValue(item)
			if err != nil {
				return nil, err
			}
			list[i] = dv
		}
		return list, nil
	case map[string]any:
		m := make(document.Map, len(vv))
		for k, item := range vv {
			dv, err := jsonToValue(item)
			if err != nil {
				return nil, err
			}
			m[k] = dv
		}
		return m, nil
	default:
		return nil, fmt.Errorf("unexpected JSON type %T", v)
	}
}
