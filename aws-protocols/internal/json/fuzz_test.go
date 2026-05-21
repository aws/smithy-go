package json

import (
	"io"
	"testing"
)

// Adapted from the Go 1.24 standard library's encoding/json FuzzDecoderToken.
//
// https://cs.opensource.google/go/go/+/refs/tags/go1.24.0:src/encoding/json/fuzz_test.go
func FuzzParser(f *testing.F) {
	// Seed corpus from stdlib FuzzDecoderToken.
	f.Add([]byte(`{
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
}`))

	// Additional seeds exercising edge cases.
	f.Add([]byte(`null`))
	f.Add([]byte(`true`))
	f.Add([]byte(`false`))
	f.Add([]byte(`0`))
	f.Add([]byte(`""`))
	f.Add([]byte(`"\u0000"`))
	f.Add([]byte(`"\uD834\uDD1E"`))
	f.Add([]byte(`{}`))
	f.Add([]byte(`[]`))
	f.Add([]byte(`{"a":1,"b":[2,3],"c":{"d":true}}`))
	f.Add([]byte(`[[[[[[[[[[[[[[[1]]]]]]]]]]]]]]]`))
	f.Add([]byte(`"\/""`))
	f.Add([]byte(`-1.23e+45`))

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
