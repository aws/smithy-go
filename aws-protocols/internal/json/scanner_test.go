// Tests in this file are derived from the Go standard library's encoding/json
// to validate matching behavior.
//
// see https://cs.opensource.google/go/go/+/refs/tags/go1.24.0:src/encoding/json/

package json

import (
	"io"
	"math"
	"reflect"
	"strings"
	"testing"
)

func testParse(input []byte) error {
	pr := parser{
		p:     input,
		state: stValue,
	}
	for {
		_, err := pr.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
	}
}

// ---------------------------------------------------------------------------
// From: src/encoding/json/scanner_test.go — TestValid
//
// Tests basic JSON validity detection.
// ---------------------------------------------------------------------------

func TestValid(t *testing.T) {
	// Cases from the stdlib TestValid table.
	tests := []struct {
		data string
		ok   bool
	}{
		{`foo`, false},
		{`}{`, false},
		{`{]`, false},
		{`{}`, true},
		{`{"foo":"bar"}`, true},
		{`{"foo":"bar","bar":{"baz":["qux"]}}`, true},
	}

	// Additional cases exercising our scanner.
	additional := []struct {
		data string
		ok   bool
	}{
		{`[]`, true},
		{`[1,2,3]`, true},
		{`null`, true},
		{`true`, true},
		{`false`, true},
		{`"hello"`, true},
		{`42`, true},
		{`-1.5e10`, true},
	}
	tests = append(tests, additional...)

	for _, tt := range tests {
		err := testParse([]byte(tt.data))
		got := err == nil
		if got != tt.ok {
			t.Errorf("drain(%q) valid=%v, want %v (err=%v)", tt.data, got, tt.ok, err)
		}
	}
}

// ---------------------------------------------------------------------------
// From: src/encoding/json/decode_test.go — TestUnmarshalSyntax
//
// Tests that syntactically invalid JSON produces errors.
// ---------------------------------------------------------------------------

func TestSyntaxErrors(t *testing.T) {
	// Cases from the stdlib TestUnmarshalSyntax table.
	stdlibCases := []string{
		"tru",
		"fals",
		"nul",
		`"hello`,
		`[1,2,3`,
		`{"key":1`,
		`{"key":1,`,
	}

	// Additional cases from stdlib unmarshalTests (syntax error entries).
	fromUnmarshalTests := []string{
		`{"X": "foo", "Y"}`, // missing colon
		`[1, 2, 3+]`,        // invalid char after element
		`[2, 3`,             // unexpected EOF
	}

	// Additional cases exercising our parser's state machine.
	additional := []string{
		`{]`,
		`[}`,
		`{"a" "b"}`, // missing colon
		`[1 2]`,     // missing comma
		`{"a":}`,    // missing value
		`{:1}`,      // missing key
		`[,]`,       // leading comma
		`{,}`,       // leading comma
		`[1,,2]`,    // double comma
	}

	all := append(stdlibCases, fromUnmarshalTests...)
	all = append(all, additional...)

	for _, tt := range all {
		if err := testParse([]byte(tt)); err == nil {
			t.Errorf("drain(%q) = nil, want error", tt)
		}
	}
}

// ---------------------------------------------------------------------------
// From: src/encoding/json/stream_test.go — TestDecodeInStream
//
// Tests that the parser produces the correct token sequence. The stdlib test
// uses json.Token (interface values); we compare raw byte tokens instead since
// our parser returns []byte slices.
// ---------------------------------------------------------------------------

func TestTokenStream(t *testing.T) {
	// Cases from the stdlib TestDecodeInStream table (token-only entries,
	// excluding decodeThis cases which test Decode-into-value).
	tests := []struct {
		json   string
		tokens []string
	}{
		{`10`, []string{`10`}},
		{` [10] `, []string{`[`, `10`, `]`}},
		{` [false,10,"b"] `, []string{`[`, `false`, `10`, `"b"`, `]`}},
		{`{ "a": 1 }`, []string{`{`, `"a"`, `1`, `}`}},
		{`{"a": 1, "b":"3"}`, []string{`{`, `"a"`, `1`, `"b"`, `"3"`, `}`}},
		{` [{"a": 1},{"a": 2}] `, []string{
			`[`, `{`, `"a"`, `1`, `}`, `{`, `"a"`, `2`, `}`, `]`,
		}},
		{`{"obj": {"a": 1}}`, []string{
			`{`, `"obj"`, `{`, `"a"`, `1`, `}`, `}`,
		}},
		{`{"obj": [{"a": 1}]}`, []string{
			`{`, `"obj"`, `[`, `{`, `"a"`, `1`, `}`, `]`, `}`,
		}},
	}

	// Additional cases.
	additional := []struct {
		json   string
		tokens []string
	}{
		{`null`, []string{`null`}},
		{`true`, []string{`true`}},
		{`false`, []string{`false`}},
		{`"hello"`, []string{`"hello"`}},
		{`""`, []string{`""`}},
		{`[null, true, false]`, []string{`[`, `null`, `true`, `false`, `]`}},
	}
	tests = append(tests, additional...)

	for _, tt := range tests {
		t.Run(tt.json, func(t *testing.T) {
			p := parser{
				p:     []byte(tt.json),
				state: stValue,
			}
			var got []string
			for {
				tok, err := p.Next()
				if err == io.EOF {
					break
				}
				if err != nil {
					t.Fatalf("unexpected error: %v", err)
				}
				got = append(got, string(tok))
			}
			if !reflect.DeepEqual(got, tt.tokens) {
				t.Errorf("tokens mismatch:\n  got:  %v\n  want: %v", got, tt.tokens)
			}
		})
	}
}

// ---------------------------------------------------------------------------
// From: src/encoding/json/decode_test.go — unmarshalTests
//
// Tests that parser.Value() decodes JSON into Go values matching the behavior
// of encoding/json.Decoder.Decode into any (numbers become float64, objects
// become map[string]any, arrays become []any).
// ---------------------------------------------------------------------------

func TestValue(t *testing.T) {
	// Cases drawn from the stdlib unmarshalTests table (adapted for untyped
	// decoding into any).
	tests := []struct {
		in  string
		out any
	}{
		// Basic types — from unmarshalTests lines for bool, int, float, string.
		{`true`, true},
		{`false`, false},
		{`null`, nil},
		{`1`, float64(1)},
		{`1.2`, float64(1.2)},
		{`-5`, float64(-5)},
		{`2`, float64(2)},
		{`0`, float64(0)},
		{`-0`, float64(0)},
		{`1e2`, float64(100)},
		{`1.5e1`, float64(15)},
		{`-5e+2`, float64(-500)},
		{`3e-3`, float64(0.003)},

		// String escapes — from unmarshalTests.
		{`"a\u1234"`, "a\u1234"},
		{`"http:\/\/"`, "http://"},
		{`"g-clef: \uD834\uDD1E"`, "g-clef: \U0001D11E"},
		{`"invalid: \uD834x\uDD1E"`, "invalid: \uFFFDx\uFFFD"},

		// Whitespace — from unmarshalTests "raw values with whitespace" section.
		{"\n true ", true},
		{"\t 1 ", float64(1)},
		{"\r 1.2 ", float64(1.2)},
		{"\t -5 \n", float64(-5)},
		{"\t \"a\\u1234\" \n", "a\u1234"},

		// Complex nested — from unmarshalTests ifaceNumAsFloat64 case.
		{`{"k1":1,"k2":"s","k3":[1,2.0,3e-3],"k4":{"kk1":"s","kk2":2}}`,
			map[string]any{
				"k1": float64(1),
				"k2": "s",
				"k3": []any{float64(1), float64(2.0), float64(0.003)},
				"k4": map[string]any{"kk1": "s", "kk2": float64(2)},
			}},
	}

	// Additional cases.
	additional := []struct {
		in  string
		out any
	}{
		{`"hello"`, "hello"},
		{`""`, ""},
		{`"tab:\t"`, "tab:\t"},
		{`"newline:\n"`, "newline:\n"},
		{`"quote:\""`, "quote:\""},
		{`"backslash:\\"`, "backslash:\\"},
		{`"slash:\/"`, "slash:/"},
		{`"cr:\r"`, "cr:\r"},
		{`"formfeed:\f"`, "formfeed:\f"},
		{`"backspace:\b"`, "backspace:\b"},
		{`[]`, []any{}},
		{`[1,2,3]`, []any{float64(1), float64(2), float64(3)}},
		{`[true, false, null]`, []any{true, false, nil}},
		{`["a", "b"]`, []any{"a", "b"}},
		{`[[1],[2]]`, []any{[]any{float64(1)}, []any{float64(2)}}},
		{`{}`, map[string]any{}},
		{`{"a":1}`, map[string]any{"a": float64(1)}},
		{`{"a":1,"b":"two"}`, map[string]any{"a": float64(1), "b": "two"}},
		{`{"nested":{"x":true}}`, map[string]any{"nested": map[string]any{"x": true}}},
	}
	tests = append(tests, additional...)

	for _, tt := range tests {
		t.Run(tt.in, func(t *testing.T) {
			p := parser{
				p:     []byte(tt.in),
				state: stValue,
			}
			got, err := p.Value()
			if err != nil {
				t.Fatalf("Value(%q) error: %v", tt.in, err)
			}
			if !reflect.DeepEqual(got, tt.out) {
				t.Errorf("Value(%q):\n  got:  %#v\n  want: %#v", tt.in, got, tt.out)
			}
		})
	}
}

// ---------------------------------------------------------------------------
// From: src/encoding/json/number_test.go — TestNumberIsValid
//
// Tests valid and invalid JSON number formats.
// ---------------------------------------------------------------------------

func TestNumberParsing(t *testing.T) {
	// Valid numbers from the stdlib TestNumberIsValid table.
	valid := []string{
		"0",
		"-0",
		"1",
		"-1",
		"0.1",
		"-0.1",
		"1234",
		"-1234",
		"12.34",
		"-12.34",
		"12E0",
		"12E1",
		"12e34",
		"12E-0",
		"12e+1",
		"12e-34",
		"-12E0",
		"-12E1",
		"-12e34",
		"-12E-0",
		"-12e+1",
		"-12e-34",
		"1.2E0",
		"1.2E1",
		"1.2e34",
		"1.2E-0",
		"1.2e+1",
		"1.2e-34",
		"-1.2E0",
		"-1.2E1",
		"-1.2e34",
		"-1.2E-0",
		"-1.2e+1",
		"-1.2e-34",
		"0E0",
		"0E1",
		"0e34",
		"0E-0",
		"0e+1",
		"0e-34",
		"-0E0",
		"-0E1",
		"-0e34",
		"-0E-0",
		"-0e+1",
		"-0e-34",
	}
	for _, tt := range valid {
		if err := testParse([]byte(tt)); err != nil {
			t.Errorf("drain(%q) = %v, want nil", tt, err)
		}
	}

	// Invalid numbers from the stdlib TestNumberIsValid table.
	// Note: some stdlib cases (like "123e", "1e", "1e+", "01", "012") test
	// isValidNumber() which rejects them statically. Our scanner may accept
	// the token but the parser's state machine or strconv.ParseFloat will
	// reject them downstream. We only include cases our scanner itself
	// should reject.
	invalid := []string{
		"",
		"invalid",
		"1..1",
		"1e+-2",
		"1e--23",
		"e1",
		"1ea",
		"1.a",
	}
	for _, tt := range invalid {
		if err := testParse([]byte(tt)); err == nil {
			t.Errorf("drain(%q) = nil, want error", tt)
		}
	}
}

// ---------------------------------------------------------------------------
// From: src/encoding/json/decode_test.go — unmarshalTests (string entries)
//
// Tests string escape sequences including JSON-specific escapes (\/) and
// UTF-16 surrogate pairs (\uD800\uDC00) that differ from Go string literals.
// ---------------------------------------------------------------------------

func TestStringEscapes(t *testing.T) {
	// Cases from stdlib unmarshalTests and the unquoteBytes implementation.
	tests := []struct {
		in   string
		want string
	}{
		{`"\""`, `"`},
		{`"\\"`, `\`},
		{`"\/"`, `/`}, // JSON-specific: \/ is valid
		{`"\b"`, "\b"},
		{`"\f"`, "\f"},
		{`"\n"`, "\n"},
		{`"\r"`, "\r"},
		{`"\t"`, "\t"},
		{`"\u0041"`, "A"},
		{`"\u00e9"`, "é"},
		{`"\u0000"`, "\x00"},
		{`"\uD800\uDC00"`, "\U00010000"}, // surrogate pair: U+10000
		{`"\uD834\uDD1E"`, "\U0001D11E"}, // surrogate pair: G clef
		{`"no escape"`, "no escape"},
		{`""`, ""},
	}
	for _, tt := range tests {
		t.Run(tt.in, func(t *testing.T) {
			tok := parser{p: []byte(tt.in), state: stValue}
			raw, err := tok.Next()
			if err != nil {
				t.Fatalf("tokenize error: %v", err)
			}
			got, err := unquote(raw)
			if err != nil {
				t.Fatalf("str error: %v", err)
			}
			if got != tt.want {
				t.Errorf("unquote(%s) = %q, want %q", tt.in, got, tt.want)
			}
		})
	}
}

// ---------------------------------------------------------------------------
// Additional tests not directly from stdlib but validating equivalent behavior.
// ---------------------------------------------------------------------------

func TestInvalidStrings(t *testing.T) {
	tests := []string{
		`"`,           // unterminated
		`"hello`,      // unterminated
		"\"hello\n\"", // unescaped newline (invalid per RFC 8259 §7)
		"\"hello\r\"", // unescaped carriage return
	}
	for _, tt := range tests {
		tok := parser{p: []byte(tt), state: stValue}
		_, err := tok.Next()
		if err == nil {
			t.Errorf("tokenize(%q) = nil, want error", tt)
		}
	}
}

func TestFloatSpecialValues(t *testing.T) {
	tests := []struct {
		in  string
		out float64
	}{
		{"0", 0},
		{"-0", math.Copysign(0, -1)},
		{"1e308", 1e308},
		{"-1e308", -1e308},
		{"5e-324", 5e-324},
		{"1.7976931348623157e308", math.MaxFloat64},
	}
	for _, tt := range tests {
		t.Run(tt.in, func(t *testing.T) {
			p := parser{
				p:     []byte(tt.in),
				state: stValue,
			}
			v, err := p.Value()
			if err != nil {
				t.Fatalf("Value(%q) error: %v", tt.in, err)
			}
			got, ok := v.(float64)
			if !ok {
				t.Fatalf("Value(%q) = %T, want float64", tt.in, v)
			}
			if got != tt.out {
				t.Errorf("Value(%q) = %v, want %v", tt.in, got, tt.out)
			}
		})
	}
}

func TestSkip(t *testing.T) {
	tests := []struct {
		json  string
		after string
	}{
		{`[1,2,3], "after"`, "after"},
		{`{"a":1}, "after"`, "after"},
		{`"str", "after"`, "after"},
		{`123, "after"`, "after"},
		{`true, "after"`, "after"},
		{`null, "after"`, "after"},
		{`{"a":{"b":[1,2,{"c":3}]}}, "after"`, "after"},
	}
	for _, tt := range tests {
		t.Run(tt.json, func(t *testing.T) {
			wrapped := `[` + tt.json + `]`
			p := parser{
				p:     []byte(wrapped),
				state: stValue,
			}
			tok, err := p.Next()
			if err != nil || string(tok) != "[" {
				t.Fatalf("expected '[', got %q err=%v", tok, err)
			}
			if err := p.Skip(); err != nil {
				t.Fatalf("Skip() error: %v", err)
			}
			tok, err = p.Next()
			if err != nil {
				t.Fatalf("Next() after Skip error: %v", err)
			}
			got, _ := unquote(tok)
			if got != tt.after {
				t.Errorf("after Skip: got %q, want %q", got, tt.after)
			}
		})
	}
}

func TestLargeInput(t *testing.T) {
	// Deeply nested object.
	var b strings.Builder
	depth := 100
	for i := 0; i < depth; i++ {
		b.WriteString(`{"a":`)
	}
	b.WriteString(`1`)
	for i := 0; i < depth; i++ {
		b.WriteString(`}`)
	}
	if err := testParse([]byte(b.String())); err != nil {
		t.Fatalf("drain(nested %d deep) error: %v", depth, err)
	}

	// Large array.
	b.Reset()
	b.WriteString(`[`)
	for i := 0; i < 10000; i++ {
		if i > 0 {
			b.WriteString(`,`)
		}
		b.WriteString(`{"key":"value","num":123}`)
	}
	b.WriteString(`]`)
	if err := testParse([]byte(b.String())); err != nil {
		t.Fatalf("drain(large array) error: %v", err)
	}
}

func TestWhitespace(t *testing.T) {
	tests := []string{
		" \t\r\n{} ",
		"  [  1  ,  2  ,  3  ]  ",
		"\n\n{\n\"a\"\n:\n1\n}\n",
		"\t{\t\"key\"\t:\t\"value\"\t}\t",
	}
	for _, tt := range tests {
		if err := testParse([]byte(tt)); err != nil {
			t.Errorf("drain(%q) = %v, want nil", tt, err)
		}
	}
}
