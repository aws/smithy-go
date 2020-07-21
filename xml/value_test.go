package xml

import (
	"bytes"
	"fmt"
	"math"
	"math/big"
	"strconv"
	"testing"
)

var (
	oneInt   = new(big.Int).SetInt64(1)
	oneFloat = new(big.Float).SetFloat64(1.0)
)

func TestValue(t *testing.T) {
	cases := map[string]struct {
		setter   func(Value)
		expected string
	}{
		"string value": {
			setter: func(value Value) {
				value.String("foo")
			},
			expected: `foo`,
		},
		"string escaped": {
			setter: func(value Value) {
				value.String("{\"foo\":\"bar\"}")
			},
			expected: fmt.Sprintf("{%sfoo%s:%sbar%s}", escQuot, escQuot, escQuot, escQuot),
		},
		"integer": {
			setter: func(value Value) {
				value.Long(1024)
			},
			expected: `1024`,
		},
		"float": {
			setter: func(value Value) {
				value.Double(1e20)
			},
			expected: `1e+20`,
		},
		"float exponent component": {
			setter: func(value Value) {
				value.Double(3e22)
			},
			expected: `3e+22`,
		},
		"boolean true": {
			setter: func(value Value) {
				value.Boolean(true)
			},
			expected: `true`,
		},
		"boolean false": {
			setter: func(value Value) {
				value.Boolean(false)
			},
			expected: `false`,
		},
		"encode bytes": {
			setter: func(value Value) {
				value.Base64EncodeBytes([]byte("foo bar"))
			},
			expected: `Zm9vIGJhcg==`,
		},
		"encode bytes nil": {
			setter: func(value Value) {
				value.Base64EncodeBytes(nil)
			},
			expected: ``,
		},
		"object": {
			setter: func(value Value) {
				o := value.NestedElement()
				defer o.Close()
				o.Key("key", nil).String("value")
			},
			expected: `<key>value</key>`,
		},
		"array": {
			setter: func(value Value) {
				o := value.Array()
				defer o.Close()
				o.Member().String("value1")
				o.Member().String("value2")
			},
			expected: `<member>value1</member><member>value2</member>`,
		},
		"null": {
			setter: func(value Value) {
				value.Null()
			},
			expected: ``,
		},
		"nullWithRoot": {
			setter: func(value Value) {
				o := value.NestedElement()
				defer o.Close()
				o.Key("parent", nil).Null()
			},
			expected: `<parent></parent>`,
		},
		"write text": {
			setter: func(value Value) {
				o := value.NestedElement()
				defer o.Close()

				o.Key("inline", nil).Write([]byte(`{"nested":"value"}`), false)
			},
			expected: `<inline>{"nested":"value"}</inline>`,
		},
		"write escaped text": {
			setter: func(value Value) {
				o := value.NestedElement()
				defer o.Close()
				o.Key("inline", nil).Write([]byte(`{"nested":"value"}`), true)
			},
			expected: fmt.Sprintf("<inline>{%snested%s:%svalue%s}</inline>", escQuot, escQuot, escQuot, escQuot),
		},
		"bigInteger": {
			setter: func(value Value) {
				v := new(big.Int).SetInt64(math.MaxInt64)
				value.BigInteger(v.Sub(v, oneInt))
			},
			expected: strconv.FormatInt(math.MaxInt64-1, 10),
		},
		"bigInteger > int64": {
			setter: func(value Value) {
				v := new(big.Int).SetInt64(math.MaxInt64)
				value.BigInteger(v.Add(v, oneInt))
			},
			expected: "9223372036854775808",
		},
		"bigInteger < int64": {
			setter: func(value Value) {
				v := new(big.Int).SetInt64(math.MinInt64)
				value.BigInteger(v.Sub(v, oneInt))
			},
			expected: "-9223372036854775809",
		},
		"bigFloat": {
			setter: func(value Value) {
				v := new(big.Float).SetFloat64(math.MaxFloat64)
				value.BigDecimal(v.Sub(v, oneFloat))
			},
			expected: strconv.FormatFloat(math.MaxFloat64-1, 'e', -1, 64),
		},
		"bigFloat fits in int64": {
			setter: func(value Value) {
				v := new(big.Float).SetInt64(math.MaxInt64)
				value.BigDecimal(v)
			},
			expected: "9223372036854775807",
		},
	}
	scratch := make([]byte, 64)

	for name, tt := range cases {
		t.Run(name, func(t *testing.T) {
			var b bytes.Buffer
			value := newValue(&b, &scratch, nil, nil)

			tt.setter(value)

			if e, a := []byte(tt.expected), b.Bytes(); bytes.Compare(e, a) != 0 {
				t.Errorf("expected %+q, but got %+q", e, a)
			}
		})
	}
}
