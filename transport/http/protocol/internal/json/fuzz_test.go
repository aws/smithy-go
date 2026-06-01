package json

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"reflect"
	"testing"
	"unicode/utf8"
)

// FuzzParser checks that the parser doesn't crash on arbitrary input.
func FuzzParser(f *testing.F) {
	for _, seed := range fuzzSeeds {
		f.Add(seed)
	}

	f.Fuzz(func(t *testing.T, b []byte) {
		p := parser{
			p:     b,
			state: stValue,
		}
		for {
			_, err := p.Next()
			if err != nil {
				if err == io.EOF {
					break
				}
				return
			}
		}
	})
}

// FuzzParserDifferential compares our parser's accept/reject and parsed values
// against encoding/json. Any input accepted by one but rejected by the other
// (or producing different values) is a bug.
func FuzzParserDifferential(f *testing.F) {
	for _, seed := range fuzzSeeds {
		f.Add(seed)
	}

	f.Fuzz(func(t *testing.T, b []byte) {
		var stdVal any
		stdErr := json.Unmarshal(b, &stdVal)

		p := parser{
			p:     b,
			state: stValue,
		}
		ourVal, ourErr := p.Value()
		if ourErr == nil {
			// parser.Value() reads one value from a stream and stops. It does
			// not reject trailing content, but json.Unmarshal does. Check
			// explicitly so the comparison is apples-to-apples.
			for i := p.i; i < len(p.p); i++ {
				if p.p[i] != ' ' && p.p[i] != '\t' && p.p[i] != '\n' && p.p[i] != '\r' {
					ourErr = fmt.Errorf("trailing content at offset %d", i)
					break
				}
			}
		}

		stdOK := stdErr == nil
		ourOK := ourErr == nil

		if stdOK != ourOK {
			t.Errorf("accept/reject mismatch\ninput: %q\nstdlib ok: %v (err: %v)\nours ok:   %v (err: %v)\nour value: %v",
				b, stdOK, stdErr, ourOK, ourErr, ourVal)
			return
		}

		if !stdOK {
			return
		}

		if !reflect.DeepEqual(normalize(stdVal), normalize(ourVal)) {
			t.Errorf("value mismatch\ninput: %q\nstdlib: %v\nours:   %v", b, stdVal, ourVal)
		}
	})
}

// normalize recursively walks a parsed value tree. Both parsers produce the
// same types (float64 for numbers, string, bool, nil, []any, map[string]any)
// so this is mainly defensive.
func normalize(v any) any {
	switch vv := v.(type) {
	case map[string]any:
		for k, val := range vv {
			vv[k] = normalize(val)
		}
		return vv
	case []any:
		for i, val := range vv {
			vv[i] = normalize(val)
		}
		return vv
	default:
		return v
	}
}

var fuzzSeeds = [][]byte{
	[]byte(`{
"object": {
	"slice": [
		1,
		2.0,
		"3",
		[4],
		{5: {}}
	]
},
"slice": [[]],
"string": ":)",
"int": 1e5,
"float": 3e-9"
}`),
	[]byte(`null`),
	[]byte(`true`),
	[]byte(`false`),
	[]byte(`0`),
	[]byte(`""`),
	[]byte(`"\u0000"`),
	[]byte(`"\uD834\uDD1E"`),
	[]byte(`{}`),
	[]byte(`[]`),
	[]byte(`{"a":1,"b":[2,3],"c":{"d":true}}`),
	[]byte(`[[[[[[[[[[[[[[[1]]]]]]]]]]]]]]]`),
	[]byte(`"\/"`),
	[]byte(`-1.23e+45`),
	[]byte(`{"key": "value", "num": 42, "bool": true, "null": null}`),
	[]byte(`[1, 2, 3, "hello", null, true, false, 1.5e10]`),
	[]byte(`"hello\nworld\t"`),
	[]byte(`{"escaped":"quote\"inside"}`),
	[]byte(`1e308`),
	[]byte(`-1e308`),
	[]byte(`5e-324`),
}

// FuzzSerializer builds a random value DOM from fuzz bytes, serializes it with
// ShapeSerializer, and verifies the output is valid JSON that round-trips to
// the same value through encoding/json.
func FuzzSerializer(f *testing.F) {
	f.Add([]byte{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
	f.Add([]byte{4, 5, 0xff, 0xfe, 0x00, 0x01, 0x1f, 0x7f, 0xc0, 0x80})
	f.Add([]byte{6, 3, 4, 2, 'h', 'i', 0, 1, 3, 0x80, 0xff, 0x22, 0x5c})

	f.Fuzz(func(t *testing.T, data []byte) {
		c := &fuzzConsumer{data: data}
		dom := buildFuzzValue(c, 0)

		s := NewShapeSerializer()
		writeFuzzValue(s, dom)
		out := s.Bytes()
		s.Close()

		var parsed any
		if err := json.Unmarshal(out, &parsed); err != nil {
			t.Fatalf("invalid JSON output: %v\nraw: %s", err, out)
		}

		if expected, ok := normalizeForJSON(dom); ok {
			if !reflect.DeepEqual(normalize(parsed), normalize(expected)) {
				t.Errorf("round-trip mismatch\ndom:      %v\nexpected: %v\nparsed:   %v\nraw:      %s",
					dom, expected, parsed, out)
			}
		}
	})
}

type fuzzConsumer struct {
	data []byte
	pos  int
}

func (c *fuzzConsumer) readByte() byte {
	if c.pos >= len(c.data) {
		return 0
	}
	b := c.data[c.pos]
	c.pos++
	return b
}

func (c *fuzzConsumer) readBytes(n int) []byte {
	if c.pos+n > len(c.data) {
		n = len(c.data) - c.pos
	}
	b := c.data[c.pos : c.pos+n]
	c.pos += n
	return b
}

func buildFuzzValue(c *fuzzConsumer, depth int) any {
	if depth > 4 {
		return nil
	}

	switch c.readByte() % 7 {
	case 0:
		return nil
	case 1:
		return c.readByte()%2 == 0
	case 2:
		return int64(int8(c.readByte()))
	case 3:
		b := c.readBytes(8)
		if len(b) < 8 {
			b = append(b, make([]byte, 8-len(b))...)
		}
		f := math.Float64frombits(binary.LittleEndian.Uint64(b))
		// exclude NaN/Inf — they serialize as strings, not numbers
		if math.IsNaN(f) || math.IsInf(f, 0) {
			return 0.0
		}
		return f
	case 4:
		n := int(c.readByte() % 32)
		return string(c.readBytes(n))
	case 5:
		n := int(c.readByte() % 4)
		list := make([]any, n)
		for i := range list {
			list[i] = buildFuzzValue(c, depth+1)
		}
		return list
	case 6:
		n := int(c.readByte() % 4)
		m := make(map[string]any, n)
		for range n {
			k := string(c.readBytes(int(c.readByte() % 8)))
			m[k] = buildFuzzValue(c, depth+1)
		}
		return m
	}
	return nil
}

func writeFuzzValue(s *ShapeSerializer, v any) {
	switch vv := v.(type) {
	case nil:
		s.WriteNil(nil)
	case bool:
		s.WriteBool(nil, vv)
	case int64:
		s.WriteInt64(nil, vv)
	case float64:
		s.WriteFloat64(nil, vv)
	case string:
		s.WriteString(nil, vv)
	case []any:
		s.WriteList(nil)
		for _, item := range vv {
			writeFuzzValue(s, item)
		}
		s.CloseList()
	case map[string]any:
		s.WriteMap(nil)
		for k, item := range vv {
			s.WriteKey(nil, k)
			writeFuzzValue(s, item)
		}
		s.CloseMap()
	}
}

// normalizeForJSON converts our DOM types to what encoding/json would produce
// after unmarshal (all numbers become float64, nil in lists stays nil).
// Strings with invalid UTF-8 are normalized the same way the serializer
// handles them (replace invalid bytes with U+FFFD).
// Returns false if the value contains map key collisions after normalization,
// meaning a clean round-trip is impossible.
func normalizeForJSON(v any) (any, bool) {
	switch vv := v.(type) {
	case int64:
		return float64(vv), true
	case string:
		return normalizeString(vv), true
	case []any:
		out := make([]any, len(vv))
		for i, item := range vv {
			val, ok := normalizeForJSON(item)
			if !ok {
				return nil, false
			}
			out[i] = val
		}
		return out, true
	case map[string]any:
		out := make(map[string]any, len(vv))
		for k, item := range vv {
			nk := normalizeString(k)
			if _, exists := out[nk]; exists {
				return nil, false
			}
			val, ok := normalizeForJSON(item)
			if !ok {
				return nil, false
			}
			out[nk] = val
		}
		return out, true
	default:
		return v, true
	}
}

func normalizeString(s string) string {
	if utf8.ValidString(s) {
		return s
	}

	var b []byte
	for i := 0; i < len(s); {
		r, size := utf8.DecodeRuneInString(s[i:])
		if r == utf8.RuneError && size == 1 {
			b = utf8.AppendRune(b, utf8.RuneError)
		} else {
			b = append(b, s[i:i+size]...)
		}
		i += size
	}
	return string(b)
}
