package json

import (
	"encoding/base64"
	"fmt"
	"math"
	"strconv"
	"strings"
	"time"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/aws-protocols/internal/json/internal/stdlib"
	"github.com/aws/smithy-go/document"
	smithytime "github.com/aws/smithy-go/time"
	"github.com/aws/smithy-go/traits"
)

// ShapeDeserializer implements unmarshaling of JSON into Smithy shapes.
type ShapeDeserializer struct {
	p    parser
	head stackT[any]
	opts Options

	// it's easier to just maintain the "peeked" token here actually
	peeked []byte
}

// NewShapeDeserializer creates a new ShapeDeserializer.
func NewShapeDeserializer(p []byte, opts ...func(*Options)) *ShapeDeserializer {
	o := Options{}
	for _, fn := range opts {
		fn(&o)
	}
	return &ShapeDeserializer{
		p: parser{
			tok:   scanner{p: p},
			parse: (*parser).parseValue,
		},
		opts: o,
	}
}

var _ smithy.ShapeDeserializer = (*ShapeDeserializer)(nil)

func (d *ShapeDeserializer) next() ([]byte, error) {
	if d.peeked != nil {
		peeked := d.peeked
		d.peeked = nil
		return peeked, nil
	}
	return d.p.Next()
}

func (d *ShapeDeserializer) peek() ([]byte, error) {
	if d.peeked != nil {
		return d.peeked, nil
	}
	tok, err := d.p.Next()
	if err != nil {
		return nil, err
	}
	d.peeked = tok
	return tok, nil
}

// ReadNil implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadNil(s *smithy.Schema) (bool, error) {
	tok, err := d.peek()
	if err != nil {
		return false, err
	}
	if isN(tok) {
		d.peeked = nil
		return true, nil
	}
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
	tok, err := d.next()
	if err != nil {
		return 0, err
	}

	if isS(tok) || isLCB(tok) || isLSB(tok) {
		return 0, fmt.Errorf("expected number, got %s", tok)
	}

	n, err := strconv.ParseInt(string(tok), 10, 64)
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
	tok, err := d.next()
	if err != nil {
		return 0, err
	}

	if isS(tok) {
		s, err := unquote(tok)
		if err != nil {
			return 0, err
		}
		switch {
		case strings.EqualFold(s, "NaN"):
			return math.NaN(), nil
		case strings.EqualFold(s, "Infinity"):
			return math.Inf(1), nil
		case strings.EqualFold(s, "-Infinity"):
			return math.Inf(-1), nil
		default:
			return 0, fmt.Errorf("unexpected string value for float: %s", s)
		}
	}

	return strconv.ParseFloat(string(tok), 64)
}

// ReadBool implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadBool(s *smithy.Schema, v *bool) error {
	tok, err := d.next()
	if err != nil {
		return err
	}

	switch {
	case isT(tok):
		*v = true
		return nil
	case isF(tok):
		*v = false
		return nil
	default:
		return fmt.Errorf("expected bool, got %s", tok)
	}
}

// ReadBoolPtr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadBoolPtr(s *smithy.Schema, v **bool) error {
	return readPtr(d, s, v, d.ReadBool)
}

// ReadString implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadString(s *smithy.Schema, v *string) error {
	tok, err := d.next()
	if err != nil {
		return err
	}
	if tok == nil {
		return nil
	}

	if !isS(tok) {
		return fmt.Errorf("expected string, got %s", tok)
	}

	sv, err := unquote(tok)
	if err != nil {
		return err
	}

	*v = sv
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

	tok, err := d.next()
	if err != nil {
		return err
	}

	if !isS(tok) {
		return fmt.Errorf("expected string, got %s", tok)
	}

	sv, err := unquote(tok)
	if err != nil {
		return err
	}

	b, err := base64.StdEncoding.DecodeString(sv)
	if err != nil {
		return fmt.Errorf("decode base64 blob: %w", err)
	}

	*v = b
	return nil
}

// ReadList implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadList(s *smithy.Schema) error {
	tok, err := d.next()
	if err != nil {
		return err
	}
	if !isLSB(tok) {
		return fmt.Errorf("expected '[', got %s", tok)
	}
	return nil
}

// ReadListItem implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadListItem(s *smithy.Schema) (bool, error) {
	tok, err := d.peek()
	if err != nil {
		return false, err
	}
	if isRSB(tok) {
		d.peeked = nil
		return false, nil
	}
	return true, nil
}

// ReadMap implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadMap(s *smithy.Schema) error {
	tok, err := d.next()
	if err != nil {
		return err
	}
	if !isLCB(tok) {
		return fmt.Errorf("expected '{', got %s", tok)
	}
	return nil
}

// ReadMapKey implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadMapKey(s *smithy.Schema) (string, bool, error) {
	tok, err := d.next()
	if err != nil {
		return "", false, err
	}
	if isRCB(tok) {
		return "", false, nil
	}

	key, err := unquote(tok)
	if err != nil {
		return "", false, err
	}
	return key, true, nil
}

// ReadStruct implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadStruct(s *smithy.Schema) error {
	if isNil, err := d.ReadNil(s); isNil || err != nil {
		return err
	}

	tok, err := d.next()
	if err != nil {
		return err
	}
	if !isLCB(tok) {
		return fmt.Errorf("expected '{', got %s", tok)
	}
	d.head.Push(s)
	return nil
}

// ReadStructMember implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadStructMember() (*smithy.Schema, error) {
	tok, err := d.next()
	if err != nil {
		return nil, err
	}
	if isRCB(tok) {
		d.head.Pop()
		return nil, nil
	}

	key, err := unquote(tok)
	if err != nil {
		return nil, err
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
		if err := d.p.Skip(); err != nil {
			return nil, err
		}
		return d.ReadStructMember()
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

		tok, err := d.next()
		if err != nil {
			return nil, err
		}
		if !isLCB(tok) {
			return nil, fmt.Errorf("expected '{', got %s", tok)
		}
		d.head.Push(&unionCtx{schema: s})
	}

	for {
		tok, err := d.next()
		if err != nil {
			return nil, err
		}
		if isRCB(tok) {
			d.head.Pop()
			return nil, nil
		}

		key, err := unquote(tok)
		if err != nil {
			return nil, err
		}

		// skip null values
		isNil, err := d.ReadNil(nil)
		if err != nil {
			return nil, err
		}
		if isNil {
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
			if err := d.p.Skip(); err != nil {
				return nil, err
			}
			continue
		}

		return member, nil
	}
}

// ReadDocument reads a JSON value into a document Value.
func (d *ShapeDeserializer) ReadDocument(schema *smithy.Schema, v *document.Value) error {
	vv, err := d.p.Value()
	if err != nil {
		return err
	}
	*v = document.Opaque{Value: vv}
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

func unquote(tok []byte) (string, error) {
	if s, ok := stdlib.UnquoteBytes(tok); ok {
		return string(s), nil
	}
	return "", fmt.Errorf("cannot unquote %s", tok)
}

func isN(tok []byte) bool   { return tok[0] == 'n' }
func isT(tok []byte) bool   { return tok[0] == 't' }
func isF(tok []byte) bool   { return tok[0] == 'f' }
func isS(tok []byte) bool   { return tok[0] == '"' }
func isLCB(tok []byte) bool { return tok[0] == '{' }
func isRCB(tok []byte) bool { return tok[0] == '}' }
func isLSB(tok []byte) bool { return tok[0] == '[' }
func isRSB(tok []byte) bool { return tok[0] == ']' }
