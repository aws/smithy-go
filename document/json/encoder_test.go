package json_test

import (
	stdlibjson "encoding/json"
	"reflect"
	"testing"
	"time"

	"github.com/aws/smithy-go/document"
	"github.com/aws/smithy-go/document/json"
)

func TestEncoder_Encode(t *testing.T) {
	t.Run("Object", func(t *testing.T) {
		for name, tt := range sharedObjectTests {
			t.Run(name, func(t *testing.T) {
				testEncode(t, tt)
			})
		}
	})
	t.Run("Array", func(t *testing.T) {
		for name, tt := range sharedArrayTestCases {
			t.Run(name, func(t *testing.T) {
				testEncode(t, tt)
			})
		}
	})
	t.Run("Number", func(t *testing.T) {
		for name, tt := range sharedNumberTestCases {
			t.Run(name, func(t *testing.T) {
				testEncode(t, tt)
			})
		}
	})
	t.Run("String", func(t *testing.T) {
		for name, tt := range sharedStringTests {
			t.Run(name, func(t *testing.T) {
				testEncode(t, tt)
			})
		}
	})
}

func TestNewEncoderUnsupportedTypes(t *testing.T) {
	type customTime time.Time
	type noSerde = document.NoSerde
	type NestedThing struct {
		SomeThing string
		noSerde
	}
	type Thing struct {
		OtherThing  string
		NestedThing NestedThing
	}

	cases := []interface{}{
		time.Now().UTC(),
		customTime(time.Now().UTC()),
		Thing{OtherThing: "foo", NestedThing: NestedThing{SomeThing: "bar"}},
	}

	encoder := json.NewEncoder()
	for _, tt := range cases {
		_, err := encoder.Encode(tt)
		if err == nil {
			t.Errorf("expect error, got nil")
		}
	}
}

func TestDeterministicMapOrder(t *testing.T) {
	value := struct {
		Map map[string]string
	}{
		Map: map[string]string{
			"foo": "bar",
			"a":   "b",
			"bar": "baz",
			"b":   "c",
			"baz": "qux",
			"c":   "d",
		},
	}
	expect := `{"Map":{"a":"b","b":"c","bar":"baz","baz":"qux","c":"d","foo":"bar"}}`

	encoder := json.NewEncoder()
	actual, err := encoder.Encode(value)
	if err != nil {
		t.Fatal(err)
	}

	if expect != string(actual) {
		t.Errorf("encode determinstic order:\n%q !=\n%q", expect, actual)
	}
}

func TestDocumentNumberEncodesAsNumber(t *testing.T) {
	encoder := json.NewEncoder()

	cases := map[string]struct {
		input    interface{}
		expected string
	}{
		"integer": {
			input:    map[string]interface{}{"x": document.Number("42")},
			expected: `{"x":42}`,
		},
		"float": {
			input:    map[string]interface{}{"x": document.Number("42.0")},
			expected: `{"x":42.0}`,
		},
		"negative zero": {
			input:    map[string]interface{}{"x": document.Number("-0.0")},
			expected: `{"x":-0.0}`,
		},
		"scientific notation": {
			input:    map[string]interface{}{"x": document.Number("1.5e+10")},
			expected: `{"x":1.5e+10}`,
		},
	}

	for name, tc := range cases {
		t.Run(name, func(t *testing.T) {
			b, err := encoder.Encode(tc.input)
			if err != nil {
				t.Fatalf("Encode() error: %v", err)
			}
			if string(b) != tc.expected {
				t.Errorf("Encode() = %s, want %s", b, tc.expected)
			}
		})
	}
}

func TestJSONNumberEncodesAsNumber(t *testing.T) {
	encoder := json.NewEncoder()
	t.Run("empty number retains zero-value behavior", func(t *testing.T) {
		actual, err := encoder.Encode(stdlibjson.Number(""))
		if err != nil {
			t.Fatal(err)
		}
		if string(actual) != `""` {
			t.Errorf("Encode() = %s, want an empty JSON string", actual)
		}
	})
	for _, number := range []string{
		"0", "-0", "7", "9007199254740993", "9223372036854775807",
		"-9007199254740993", "-9223372036854775808", "0.1234567890123456789",
		"-0.0", "1.5e+10", "1E-400", "1e400",
	} {
		t.Run(number, func(t *testing.T) {
			actual, err := encoder.Encode(stdlibjson.Number(number))
			if err != nil {
				t.Fatalf("Encode() error: %v", err)
			}
			if string(actual) != number {
				t.Errorf("Encode() = %s, want %s", actual, number)
			}
		})
	}

	t.Run("nested numbers and ordinary strings", func(t *testing.T) {
		type numericString string
		input := map[string]interface{}{
			"values": []interface{}{
				stdlibjson.Number("9007199254740993"),
				map[string]interface{}{"number": stdlibjson.Number("-0.0")},
				"9007199254740993", numericString("1e400"),
			},
		}
		expected := `{"values":[9007199254740993,{"number":-0.0},"9007199254740993","1e400"]}`
		actual, err := encoder.Encode(input)
		if err != nil {
			t.Fatalf("Encode() error: %v", err)
		}
		if string(actual) != expected {
			t.Errorf("Encode() = %s, want %s", actual, expected)
		}
	})
}

func TestJSONNumberRejectsInvalidLiterals(t *testing.T) {
	encoder := json.NewEncoder()
	for _, number := range []string{
		"01", "+1", "NaN", "Infinity", "-Infinity", "1e", "1.", "1 2", "true", `"1"`,
	} {
		t.Run(number, func(t *testing.T) {
			_, err := encoder.Encode(stdlibjson.Number(number))
			if err == nil {
				t.Fatal("expected an invalid-number error")
			}
		})
	}
}

func testEncode(t *testing.T, tt testCase) {
	t.Helper()

	e := json.NewEncoder(func(options *json.EncoderOptions) {
		*options = tt.encoderOptions
	})

	encodeBytes, err := e.Encode(tt.actual)
	if (err != nil) != tt.wantErr {
		t.Errorf("Encode() error = %v, wantErr %v", err, tt.wantErr)
	}

	expect := MustJSONUnmarshal(tt.json, !tt.disableJSONNumber)
	got := MustJSONUnmarshal(encodeBytes, !tt.disableJSONNumber)

	if !reflect.DeepEqual(expect, got) {
		t.Errorf("%v != %v", expect, got)
	}
}
