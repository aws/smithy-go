package json

import (
	"math/big"
	"testing"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/internal/bignum"
	"github.com/aws/smithy-go/prelude"
)

func bignumSchema() (root, intMember, decMember *smithy.Schema) {
	root = smithy.NewSchema(smithy.ShapeID{Namespace: "com.test", Name: "Nums"},
		smithy.ShapeTypeStructure, 2)
	intMember = root.AddMember("i", prelude.BigInteger)
	decMember = root.AddMember("d", prelude.BigDecimal)
	return root, intMember, decMember
}

func mustInt(t *testing.T, s string) *big.Int {
	t.Helper()
	n, err := bignum.ParseInteger([]byte(s))
	if err != nil {
		t.Fatalf("ParseInteger(%q): %v", s, err)
	}
	return n
}

func mustDecimal(t *testing.T, s string) smithy.BigDecimal {
	t.Helper()
	d, err := bignum.ParseDecimal([]byte(s))
	if err != nil {
		t.Fatalf("ParseDecimal(%q): %v", s, err)
	}
	return d
}

// awsJson/restJson1 keep arbitrary-precision numbers as JSON numbers.
func TestWriteBignum_AsJSONNumber(t *testing.T) {
	root, im, dm := bignumSchema()

	s := NewShapeSerializer()
	defer s.Close()
	s.WriteStruct(root)
	s.WriteBigInt(im, mustInt(t, "9223372036854775808"))
	s.WriteBigDecimal(dm, mustDecimal(t, "0.100000000000000000000001"))
	s.CloseStruct()

	want := `{"i":9223372036854775808,"d":0.100000000000000000000001}`
	if got := string(s.Bytes()); got != want {
		t.Errorf("got %s, want %s", got, want)
	}
}

// rpcv2Json requires arbitrary-precision numbers as JSON strings.
func TestWriteBignum_AsJSONString(t *testing.T) {
	root, im, dm := bignumSchema()

	s := NewShapeSerializer(func(o *Options) {
		o.UseStringForArbitraryPrecision = true
	})
	defer s.Close()
	s.WriteStruct(root)
	s.WriteBigInt(im, mustInt(t, "9223372036854775808"))
	s.WriteBigDecimal(dm, mustDecimal(t, "0.100000000000000000000001"))
	s.CloseStruct()

	want := `{"i":"9223372036854775808","d":"0.100000000000000000000001"}`
	if got := string(s.Bytes()); got != want {
		t.Errorf("got %s, want %s", got, want)
	}
}

// The mantissa/exponent decomposition must reproduce the same plain-decimal
// text on the wire, including scale (a trailing fractional zero survives).
func TestWriteBignum_PreservesScale(t *testing.T) {
	for _, v := range []string{
		"1.50",
		"100000000000000000000001.0",
		"0.0015",
		"-0.000000000000000000000001",
	} {
		t.Run(v, func(t *testing.T) {
			root, _, dm := bignumSchema()

			s := NewShapeSerializer()
			defer s.Close()
			s.WriteStruct(root)
			s.WriteBigDecimal(dm, mustDecimal(t, v))
			s.CloseStruct()

			want := `{"d":` + v + `}`
			if got := string(s.Bytes()); got != want {
				t.Errorf("got %s, want %s", got, want)
			}
		})
	}
}

// Deserialization accepts both encodings regardless of the setting, since the
// setting governs what this codec writes, not what a peer may send.
func TestReadBignum_AcceptsNumberAndString(t *testing.T) {
	for name, body := range map[string]string{
		"numbers": `{"i":9223372036854775808,"d":0.100000000000000000000001}`,
		"strings": `{"i":"9223372036854775808","d":"0.100000000000000000000001"}`,
	} {
		t.Run(name, func(t *testing.T) {
			root, _, _ := bignumSchema()

			d := NewShapeDeserializer([]byte(body))
			defer d.Close()

			var gotInt *big.Int
			var gotDec smithy.BigDecimal
			err := d.DirectReadStruct(root, func(m *smithy.Schema) error {
				switch m.MemberName() {
				case "i":
					return d.ReadBigInt(m, &gotInt)
				case "d":
					return d.ReadBigDecimal(m, &gotDec)
				}
				return nil
			})
			if err != nil {
				t.Fatalf("deserialize: %v", err)
			}

			if got, want := gotInt.String(), "9223372036854775808"; got != want {
				t.Errorf("bigInteger: got %s, want %s", got, want)
			}
			wantDec := mustDecimal(t, "0.100000000000000000000001")
			if gotDec.Mantissa.Cmp(wantDec.Mantissa) != 0 || gotDec.Exp != wantDec.Exp {
				t.Errorf("bigDecimal: got {%s,%d}, want {%s,%d}",
					gotDec.Mantissa, gotDec.Exp, wantDec.Mantissa, wantDec.Exp)
			}
		})
	}
}

// A token that is neither a JSON number nor a JSON string cannot hold decimal
// text at all, so it is still rejected structurally.
func TestReadBignum_RejectsNonTextualTokens(t *testing.T) {
	for name, body := range map[string]string{
		"bool":   `{"i":true}`,
		"object": `{"i":{}}`,
		"array":  `{"i":[]}`,
	} {
		t.Run(name, func(t *testing.T) {
			root, _, _ := bignumSchema()

			d := NewShapeDeserializer([]byte(body))
			defer d.Close()

			var got *big.Int
			err := d.DirectReadStruct(root, func(m *smithy.Schema) error {
				return d.ReadBigInt(m, &got)
			})
			if err == nil {
				t.Errorf("expected error for %s", body)
			}
		})
	}
}

// A value can now only be held as a valid *big.Int / smithy.BigDecimal, so
// content that isn't well-formed decimal text is a deserialize error rather
// than something to pass through. Content that IS well-formed but written in
// a non-canonical form (leading zeros, a leading '+') parses to the correct
// numeric value, resolving the rpcv2Json ABNF conformance question: there is
// no verbatim byte passthrough left to be non-conformant.
func TestReadBignum_NormalizesOnParse(t *testing.T) {
	for _, tt := range []struct {
		name, body, want string
	}{
		{name: "leading zeros", body: `{"i":"007"}`, want: "7"},
		{name: "leading plus", body: `{"i":"+42"}`, want: "42"},
	} {
		t.Run(tt.name, func(t *testing.T) {
			root, _, _ := bignumSchema()

			d := NewShapeDeserializer([]byte(tt.body))
			defer d.Close()

			var got *big.Int
			if err := d.DirectReadStruct(root, func(m *smithy.Schema) error {
				return d.ReadBigInt(m, &got)
			}); err != nil {
				t.Fatalf("deserialize: %v", err)
			}
			if got.String() != tt.want {
				t.Errorf("got %q, want %q", got, tt.want)
			}
		})
	}
}

// Exponent notation and non-integral text are rejected for bigInteger.
func TestReadBignum_RejectsMalformedInteger(t *testing.T) {
	for _, tt := range []struct {
		name, body string
	}{
		{name: "exponent notation", body: `{"i":"1e3"}`},
		{name: "fraction", body: `{"i":"1.5"}`},
	} {
		t.Run(tt.name, func(t *testing.T) {
			root, _, _ := bignumSchema()

			d := NewShapeDeserializer([]byte(tt.body))
			defer d.Close()

			var got *big.Int
			err := d.DirectReadStruct(root, func(m *smithy.Schema) error {
				return d.ReadBigInt(m, &got)
			})
			if err == nil {
				t.Errorf("expected error for %s", tt.body)
			}
		})
	}
}

// The deserialized value must not alias the parse buffer, which is pooled and
// reused after Close.
func TestReadBignum_DoesNotAliasParseBuffer(t *testing.T) {
	root, _, _ := bignumSchema()
	body := []byte(`{"i":"9223372036854775808"}`)

	d := NewShapeDeserializer(body)
	var got *big.Int
	if err := d.DirectReadStruct(root, func(m *smithy.Schema) error {
		return d.ReadBigInt(m, &got)
	}); err != nil {
		t.Fatalf("deserialize: %v", err)
	}
	d.Close()

	// scribble over the source buffer; the retained value must be unaffected
	// (*big.Int copies digits out during parse, so this is really just
	// confirming ReadBigInt doesn't retain a slice into body)
	for i := range body {
		body[i] = 'x'
	}
	if want := "9223372036854775808"; got.String() != want {
		t.Errorf("value aliased its input: got %s, want %s", got, want)
	}
}
