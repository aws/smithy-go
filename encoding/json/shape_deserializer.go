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
	p    []byte
	head stack
}

func NewShapeDeserializer(p []byte) *ShapeDeserializer {
	return &ShapeDeserializer{p: p}
}

func (ss *ShapeDeserializer) init() error {
	if ss.head.Len() != 0 {
		return nil // already decoded
	}

	var root any
	if err := json.NewDecoder(bytes.NewReader(ss.p)).Decode(&root); err != nil {
		return err
	}

	ss.head.Push(root)
	return nil
}

var _ smithy.ShapeDeserializer = (*ShapeDeserializer)(nil)

func (ss *ShapeDeserializer) ReadMap(s *smithy.Schema, visit func(string) error) error {
	if err := ss.init(); err != nil {
		return err
	}

	switch v := ss.head.Top().(type) {
	case map[string]any:
		for k, vv := range v {
			ss.head.Push(vv)
			if err := visit(k); err != nil {
				return fmt.Errorf("key %s: %w", err)
			}
			ss.head.Pop()
		}
	default:
		return fmt.Errorf("expect map, got %T", v)
	}

	return nil
}

func (ss *ShapeDeserializer) ReadList(s *smithy.Schema, visit func() error) error {
	if err := ss.init(); err != nil {
		return err
	}

	switch v := ss.head.Top().(type) {
	case []any:
		for i, vv := range v {
			ss.head.Push(vv)
			if err := visit(); err != nil {
				return fmt.Errorf("index %d: %w", i)
			}
			ss.head.Pop()
		}
	default:
		return fmt.Errorf("expect list, got %T", v)
	}

	return nil
}

func (ss *ShapeDeserializer) ReadBool(s *smithy.Schema) (bool, error) {
	if err := ss.init(); err != nil {
		return false, err
	}

	switch v := ss.head.Top().(type) {
	case bool:
		return v, nil
	default:
		return false, fmt.Errorf("expect bool, got %T", v)
	}

	return false, nil
}

func (ss *ShapeDeserializer) ReadString(s *smithy.Schema) (string, error) {
	if err := ss.init(); err != nil {
		return "", err
	}

	switch v := ss.head.Top().(type) {
	case string:
		return v, nil
	default:
		return "", fmt.Errorf("expect list, got %T", v)
	}

	return "", nil
}

func (ss *ShapeDeserializer) ReadInt8(s *smithy.Schema) (int8, error) {
	n, err := ss.readInt(s, math.MinInt8, math.MaxInt8)
	return int8(n), err
}

func (ss *ShapeDeserializer) ReadInt16(s *smithy.Schema) (int16, error) {
	n, err := ss.readInt(s, math.MinInt16, math.MaxInt16)
	return int16(n), err
}

func (ss *ShapeDeserializer) ReadInt32(s *smithy.Schema) (int32, error) {
	n, err := ss.readInt(s, math.MinInt32, math.MaxInt32)
	return int32(n), err
}

func (ss *ShapeDeserializer) ReadInt64(s *smithy.Schema) (int64, error) {
	n, err := ss.readInt(s, math.MinInt64, math.MaxInt64)
	return int64(n), err
}

func (ss *ShapeDeserializer) readInt(s *smithy.Schema, min, max int64) (int64, error) {
	if err := ss.init(); err != nil {
		return 0, err
	}

	switch v := ss.head.Top().(type) {
	case json.Number:
		n, err := v.Int64()
		if err != nil {
			return 0, fmt.Errorf("expect int, got %T", v)
		}
		if n < min || n > max {
			return 0, fmt.Errorf("int %d exceeds range [%d, %d]", n, min, max)
		}

		return n, nil
	default:
		return 0, fmt.Errorf("expect int, got %T", v)
	}

	return 0, nil
}

func (ss *ShapeDeserializer) ReadFloat32(s *smithy.Schema) (float32, error) {
	n, err := ss.readFloat(s)
	return float32(n), err
}

func (ss *ShapeDeserializer) ReadFloat64(s *smithy.Schema) (float64, error) {
	return ss.readFloat(s)
}

func (ss *ShapeDeserializer) readFloat(s *smithy.Schema) (float64, error) {
	if err := ss.init(); err != nil {
		return 0, err
	}

	switch v := ss.head.Top().(type) {
	case json.Number:
		n, err := v.Float64()
		if err != nil {
			return 0, fmt.Errorf("expect float, got %T", v)
		}

		return n, nil
	case string:
		switch {
		case strings.EqualFold(v, "NaN"):
			return math.NaN(), nil
		case strings.EqualFold(v, "Infinity"):
			return math.Inf(1), nil
		case strings.EqualFold(v, "-Infinity"):
			return math.Inf(-1), nil
		default:
			return 0, fmt.Errorf("expect float, got %s", v)
		}
	default:
		return 0, fmt.Errorf("expect float, got %T", v)
	}

	return 0, nil
}

func (ss *ShapeDeserializer) ReadStruct(s *smithy.Schema, visit func(*smithy.Schema) error) error {
	if err := ss.init(); err != nil {
		return err
	}

	switch v := ss.head.Top().(type) {
	case map[string]any:
		for k, vv := range v {
			ss.head.Push(vv)

			member := s.Member(k)
			if member == nil {
				// TODO jsonName
				continue
			}

			if err := visit(member); err != nil {
				return err
			}

			ss.head.Pop()
		}
	default:
		return fmt.Errorf("expect map, got %T", v)
	}

	return nil
}
