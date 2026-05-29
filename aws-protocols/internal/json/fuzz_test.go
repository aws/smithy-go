package json

import (
	"encoding/json"
	"fmt"
	"io"
	"reflect"
	"testing"
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
