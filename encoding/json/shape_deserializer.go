package json

import (
	"bytes"
	"encoding/json"
	"fmt"
	"math"
	"strings"

	"github.com/aws/smithy-go"
)

// ShapeDeserializer implements unmarshaling of JSON into Smithy shapes.
type ShapeDeserializer struct {
	dec  *json.Decoder
	head stack
}

func NewShapeDeserializer(p []byte) *ShapeDeserializer {
	dec := json.NewDecoder(bytes.NewReader(p))
	dec.UseNumber()
	return &ShapeDeserializer{dec: dec}
}

var _ smithy.ShapeDeserializer = (*ShapeDeserializer)(nil)

func (d *ShapeDeserializer) token() (json.Token, error) {
	return d.dec.Token()
}

func (d *ShapeDeserializer) expectDelim(e json.Delim) error {
	tok, err := d.dec.Token()
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

func (d *ShapeDeserializer) ReadInt8(s *smithy.Schema, v *int8) error {
	n, err := d.readInt(math.MinInt8, math.MaxInt8)
	*v = int8(n)
	return err
}

func (d *ShapeDeserializer) ReadInt16(s *smithy.Schema, v *int16) error {
	n, err := d.readInt(math.MinInt16, math.MaxInt16)
	*v = int16(n)
	return err
}

func (d *ShapeDeserializer) ReadInt32(s *smithy.Schema, v *int32) error {
	n, err := d.readInt(math.MinInt32, math.MaxInt32)
	*v = int32(n)
	return err
}

func (d *ShapeDeserializer) ReadInt64(s *smithy.Schema, v *int64) error {
	n, err := d.readInt(math.MinInt64, math.MaxInt64)
	*v = n
	return err
}

func (d *ShapeDeserializer) ReadInt8Ptr(s *smithy.Schema, v **int8) error {
	if *v == nil {
		*v = new(int8)
	}
	return d.ReadInt8(s, *v)
}

func (d *ShapeDeserializer) ReadInt16Ptr(s *smithy.Schema, v **int16) error {
	if *v == nil {
		*v = new(int16)
	}
	return d.ReadInt16(s, *v)
}

func (d *ShapeDeserializer) ReadInt32Ptr(s *smithy.Schema, v **int32) error {
	if *v == nil {
		*v = new(int32)
	}
	return d.ReadInt32(s, *v)
}

func (d *ShapeDeserializer) ReadInt64Ptr(s *smithy.Schema, v **int64) error {
	if *v == nil {
		*v = new(int64)
	}
	return d.ReadInt64(s, *v)
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

func (d *ShapeDeserializer) ReadFloat32(s *smithy.Schema, v *float32) error {
	n, err := d.readFloat()
	*v = float32(n)
	return err
}

func (d *ShapeDeserializer) ReadFloat64(s *smithy.Schema, v *float64) error {
	n, err := d.readFloat()
	*v = n
	return err
}

func (d *ShapeDeserializer) ReadFloat32Ptr(s *smithy.Schema, v **float32) error {
	if *v == nil {
		*v = new(float32)
	}
	return d.ReadFloat32(s, *v)
}

func (d *ShapeDeserializer) ReadFloat64Ptr(s *smithy.Schema, v **float64) error {
	if *v == nil {
		*v = new(float64)
	}
	return d.ReadFloat64(s, *v)
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

func (d *ShapeDeserializer) ReadBoolPtr(s *smithy.Schema, v **bool) error {
	if *v == nil {
		*v = new(bool)
	}
	return d.ReadBool(s, *v)
}

func (d *ShapeDeserializer) ReadString(s *smithy.Schema, v *string) error {
	tok, err := d.token()
	if err != nil {
		return err
	}

	str, ok := tok.(string)
	if !ok {
		return fmt.Errorf("expected string, got %T", tok)
	}

	*v = str
	return nil
}

func (d *ShapeDeserializer) ReadStringPtr(s *smithy.Schema, v **string) error {
	if *v == nil {
		*v = new(string)
	}
	return d.ReadString(s, *v)
}

func (d *ShapeDeserializer) ReadBlob(s *smithy.Schema, v *[]byte) error {
	tok, err := d.token()
	if err != nil {
		return err
	}

	str, ok := tok.(string)
	if !ok {
		return fmt.Errorf("expected string, got %T", tok)
	}

	*v = []byte(str) // TODO b64 decode
	return nil
}

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

func (d *ShapeDeserializer) ReadListItem(s *smithy.Schema) (bool, error) {
	if !d.dec.More() {
		return false, d.expectDelim(']')
	}

	return true, nil
}

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

func (d *ShapeDeserializer) ReadMapKey(s *smithy.Schema) (string, bool, error) {
	if !d.dec.More() {
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

func (d *ShapeDeserializer) ReadStruct(s *smithy.Schema) error {
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

func (d *ShapeDeserializer) ReadStructMember() (*smithy.Schema, error) {
	if !d.dec.More() {
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

	member := schema.Members[key]
	if member == nil {
		// TODO smithy.api#jsonName
		if err := d.skip(); err != nil {
			return nil, err
		}
		return d.ReadStructMember() // just try the next one
	}

	return member, nil
}

func (d *ShapeDeserializer) ReadUnion(s *smithy.Schema) (*smithy.Schema, error) {
	tok, err := d.token()
	if err != nil {
		return nil, err
	}

	delim, ok := tok.(json.Delim)
	if !ok || delim != '{' {
		return nil, fmt.Errorf("expected '{', got %v", tok)
	}

	if !d.dec.More() {
		return nil, fmt.Errorf("union must have exactly one member")
	}

	tok, err = d.token()
	if err != nil {
		return nil, err
	}

	key, ok := tok.(string)
	if !ok {
		return nil, fmt.Errorf("expected string key, got %T", tok)
	}

	member := s.Members[key]
	if member == nil {
		return nil, fmt.Errorf("unknown union variant: %s", key)
	}

	return member, nil
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
			for d.dec.More() {
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
			for d.dec.More() {
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
