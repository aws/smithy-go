package cbor

import (
	"bytes"
	"math/big"
	"testing"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/internal/bignum"
	"github.com/aws/smithy-go/prelude"
)

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

// rpcv2Cbor specifies bigInteger as an RFC 8949 bignum: tag 2 for non-negative
// values, tag 3 for negative, each wrapping a big-endian magnitude byte string.
func TestWriteBigInt_Bignum(t *testing.T) {
	for _, tt := range []struct {
		name string
		in   string
		want []byte
	}{
		{
			name: "positive exceeding uint64",
			in:   "18446744073709551616", // 2**64
			want: []byte{
				0xc2, // tag 2
				0x49, // byte string, len 9
				0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
			},
		},
		{
			name: "negative exceeding int64",
			in:   "-18446744073709551617", // -1 - 2**64
			want: []byte{
				0xc3, // tag 3
				0x49,
				0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
			},
		},
		{name: "small positive", in: "42", want: []byte{0xc2, 0x41, 0x2a}},
		{name: "zero", in: "0", want: []byte{0xc2, 0x40}},
		{name: "negative one", in: "-1", want: []byte{0xc3, 0x40}}, // -1 - 0
	} {
		t.Run(tt.name, func(t *testing.T) {
			s := NewShapeSerializer()
			s.WriteBigInt(prelude.BigInteger, mustInt(t, tt.in))
			if got := s.Bytes(); !bytes.Equal(got, tt.want) {
				t.Errorf("got % x, want % x", got, tt.want)
			}
		})
	}
}

// rpcv2Cbor specifies bigDecimal as an RFC 8949 tag 4 decimal fraction: a
// two-element array of [exponent, mantissa].
func TestWriteBigDecimal_DecimalFraction(t *testing.T) {
	for _, tt := range []struct {
		name string
		in   string
		want []byte
	}{
		{
			name: "one and a half",
			in:   "1.5",
			want: []byte{
				0xc4, // tag 4
				0x82, // array of 2
				0x20, // -1
				0x0f, // 15
			},
		},
		{
			// the scale is carried in the exponent, so 1.50 is a distinct
			// encoding from 1.5
			name: "trailing zero keeps its scale",
			in:   "1.50",
			want: []byte{
				0xc4,
				0x82,
				0x21,       // -2
				0x18, 0x96, // 150
			},
		},
		{
			name: "integral",
			in:   "42",
			want: []byte{
				0xc4,
				0x82,
				0x00,       // 0
				0x18, 0x2a, // 42
			},
		},
	} {
		t.Run(tt.name, func(t *testing.T) {
			s := NewShapeSerializer()
			s.WriteBigDecimal(prelude.BigDecimal, mustDecimal(t, tt.in))
			if got := s.Bytes(); !bytes.Equal(got, tt.want) {
				t.Errorf("got % x, want % x", got, tt.want)
			}
		})
	}
}

func TestBigIntegerRoundTrip(t *testing.T) {
	for _, v := range []string{
		"0", "1", "-1", "42", "-42",
		"9223372036854775807", "9223372036854775808",
		"-9223372036854775808", "-9223372036854775809",
		"18446744073709551616", "-18446744073709551617",
		"123456789012345678901234567890123456789012345678901234567890",
		"-123456789012345678901234567890123456789012345678901234567890",
	} {
		t.Run(v, func(t *testing.T) {
			s := NewShapeSerializer()
			s.WriteBigInt(prelude.BigInteger, mustInt(t, v))

			d := NewShapeDeserializer(s.Bytes())
			var got *big.Int
			if err := d.ReadBigInt(prelude.BigInteger, &got); err != nil {
				t.Fatalf("ReadBigInt: %v", err)
			}
			if got.String() != v {
				t.Errorf("got %s, want %s", got, v)
			}
		})
	}
}

// Every value must survive CBOR byte-for-byte in its mantissa/exponent form,
// including the scale, which is the property a binary float representation
// could not provide.
func TestBigDecimalRoundTrip(t *testing.T) {
	for _, v := range []string{
		"0", "1.5", "-1.5", "42", "-42",
		"1.50", "1.500",
		"100000000000000000000001.0",
		"0.100000000000000000000001",
		"-0.100000000000000000000001",
		"0.0015",
		"0.001",
		"123456789012345678901234567890.123456789012345678901234567890",
	} {
		t.Run(v, func(t *testing.T) {
			want := mustDecimal(t, v)

			s := NewShapeSerializer()
			s.WriteBigDecimal(prelude.BigDecimal, want)

			d := NewShapeDeserializer(s.Bytes())
			var got smithy.BigDecimal
			if err := d.ReadBigDecimal(prelude.BigDecimal, &got); err != nil {
				t.Fatalf("ReadBigDecimal: %v", err)
			}
			if got.Mantissa.Cmp(want.Mantissa) != 0 || got.Exp != want.Exp {
				t.Errorf("got {%s,%d}, want {%s,%d}", got.Mantissa, got.Exp, want.Mantissa, want.Exp)
			}
		})
	}
}

// A peer may encode a small bigInteger as a plain CBOR integer rather than a
// bignum, and a zero-exponent bigDecimal as an integer or bignum.
func TestReadBignum_AcceptsPlainIntegers(t *testing.T) {
	s := NewShapeSerializer()
	s.WriteInt64(prelude.Long, 42)
	payload := s.Bytes()

	d := NewShapeDeserializer(payload)
	var gotInt *big.Int
	if err := d.ReadBigInt(prelude.BigInteger, &gotInt); err != nil {
		t.Fatalf("ReadBigInt: %v", err)
	}
	if gotInt.String() != "42" {
		t.Errorf("bigInteger from plain int: got %s, want 42", gotInt)
	}

	d = NewShapeDeserializer(payload)
	var gotDec smithy.BigDecimal
	if err := d.ReadBigDecimal(prelude.BigDecimal, &gotDec); err != nil {
		t.Fatalf("ReadBigDecimal: %v", err)
	}
	if gotDec.Mantissa.String() != "42" || gotDec.Exp != 0 {
		t.Errorf("bigDecimal from plain int: got {%s,%d}, want {42,0}", gotDec.Mantissa, gotDec.Exp)
	}
}

func TestBignumInStruct(t *testing.T) {
	root := smithy.NewSchema(smithy.ShapeID{Namespace: "com.test", Name: "Nums"},
		smithy.ShapeTypeStructure, 2)
	im := root.AddMember("i", prelude.BigInteger)
	dm := root.AddMember("d", prelude.BigDecimal)

	s := NewShapeSerializer()
	s.WriteStruct(root)
	s.WriteBigInt(im, mustInt(t, "18446744073709551616"))
	s.WriteBigDecimal(dm, mustDecimal(t, "1.50"))
	s.CloseStruct()

	d := NewShapeDeserializer(s.Bytes())
	if err := d.ReadStruct(root); err != nil {
		t.Fatalf("ReadStruct: %v", err)
	}

	var gotInt *big.Int
	var gotDec smithy.BigDecimal
	for {
		m, err := d.ReadStructMember()
		if err != nil {
			t.Fatalf("ReadStructMember: %v", err)
		}
		if m == nil {
			break
		}
		switch m.MemberName() {
		case "i":
			if err := d.ReadBigInt(m, &gotInt); err != nil {
				t.Fatalf("ReadBigInt: %v", err)
			}
		case "d":
			if err := d.ReadBigDecimal(m, &gotDec); err != nil {
				t.Fatalf("ReadBigDecimal: %v", err)
			}
		}
	}

	if got, want := gotInt.String(), "18446744073709551616"; got != want {
		t.Errorf("bigInteger: got %s, want %s", got, want)
	}
	if gotDec.Mantissa.String() != "150" || gotDec.Exp != -2 {
		t.Errorf("bigDecimal: got {%s,%d}, want {150,-2}", gotDec.Mantissa, gotDec.Exp)
	}
}
