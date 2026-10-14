package bignum

import (
	"testing"

	"github.com/aws/smithy-go"
)

// The parse must be exact and scale-preserving, since it is the only
// lossy-looking step in the otherwise text-verbatim pipeline.
func TestDecimalRoundTrip(t *testing.T) {
	for _, tt := range []struct {
		in           string
		wantMantissa string
		wantExponent int64
		wantText     string // differs from in only where notation is normalized
	}{
		{in: "1.5", wantMantissa: "15", wantExponent: -1, wantText: "1.5"},
		{in: "-1.5", wantMantissa: "-15", wantExponent: -1, wantText: "-1.5"},

		// scale is information: a trailing zero must survive
		{in: "1.50", wantMantissa: "150", wantExponent: -2, wantText: "1.50"},
		{in: "100000000000000000000001.0", wantMantissa: "1000000000000000000000010", wantExponent: -1,
			wantText: "100000000000000000000001.0"},

		{in: "0.100000000000000000000001", wantMantissa: "100000000000000000000001", wantExponent: -24,
			wantText: "0.100000000000000000000001"},
		{in: "-0.100000000000000000000001", wantMantissa: "-100000000000000000000001", wantExponent: -24,
			wantText: "-0.100000000000000000000001"},

		{in: "42", wantMantissa: "42", wantExponent: 0, wantText: "42"},
		{in: "0", wantMantissa: "0", wantExponent: 0, wantText: "0"},
		{in: "9223372036854775808", wantMantissa: "9223372036854775808", wantExponent: 0,
			wantText: "9223372036854775808"},

		// exponent notation folds into the exponent; output is plain decimal
		{in: "1.5e-3", wantMantissa: "15", wantExponent: -4, wantText: "0.0015"},
		{in: "1.5E3", wantMantissa: "15", wantExponent: 2, wantText: "1500"},
		{in: "1e3", wantMantissa: "1", wantExponent: 3, wantText: "1000"},

		// a fraction shorter than its scale pads with leading zeros
		{in: "0.001", wantMantissa: "1", wantExponent: -3, wantText: "0.001"},
		{in: "-0.001", wantMantissa: "-1", wantExponent: -3, wantText: "-0.001"},
	} {
		t.Run(tt.in, func(t *testing.T) {
			d, err := ParseDecimal([]byte(tt.in))
			if err != nil {
				t.Fatalf("ParseDecimal(%q): %v", tt.in, err)
			}
			if got := d.Mantissa.String(); got != tt.wantMantissa {
				t.Errorf("mantissa: got %s, want %s", got, tt.wantMantissa)
			}
			if d.Exp != tt.wantExponent {
				t.Errorf("exponent: got %d, want %d", d.Exp, tt.wantExponent)
			}

			text, err := FormatDecimal(d)
			if err != nil {
				t.Fatalf("FormatDecimal: %v", err)
			}
			if got := string(text); got != tt.wantText {
				t.Errorf("round trip: got %q, want %q", got, tt.wantText)
			}
		})
	}
}

func TestParseDecimal_Rejects(t *testing.T) {
	for _, in := range []string{
		"", "abc", "1.2.3", "1e", "1e+", "0x10", "1_000", "--1", "Inf", "NaN",
		"1e9999999", // exponent digit cap
	} {
		t.Run(in, func(t *testing.T) {
			if _, err := ParseDecimal([]byte(in)); err == nil {
				t.Errorf("ParseDecimal(%q) = nil error, want error", in)
			}
		})
	}
}

func TestFormatDecimal_RejectsAbsurdExponent(t *testing.T) {
	one, _ := ParseDecimal([]byte("1"))

	big := one
	big.Exp = 1 << 24
	if _, err := FormatDecimal(big); err == nil {
		t.Error("expected error for out-of-range exponent")
	}

	neg := one
	neg.Exp = -(1 << 24)
	if _, err := FormatDecimal(neg); err == nil {
		t.Error("expected error for out-of-range negative exponent")
	}
}

func TestFormatDecimal_RejectsNilMantissa(t *testing.T) {
	if _, err := FormatDecimal(smithy.BigDecimal{}); err == nil {
		t.Error("expected error for nil Mantissa")
	}
}

func TestIntegerRoundTrip(t *testing.T) {
	for _, in := range []string{
		"0", "1", "-1", "42", "-42",
		"9223372036854775807", "9223372036854775808",
		"-9223372036854775808", "-9223372036854775809",
		"18446744073709551616", "-18446744073709551617",
		"123456789012345678901234567890123456789012345678901234567890",
		"-123456789012345678901234567890123456789012345678901234567890",
	} {
		t.Run(in, func(t *testing.T) {
			n, err := ParseInteger([]byte(in))
			if err != nil {
				t.Fatalf("ParseInteger: %v", err)
			}
			if got := string(FormatInteger(n)); got != in {
				t.Errorf("got %s, want %s", got, in)
			}
		})
	}
}

func TestParseInteger_Rejects(t *testing.T) {
	for _, in := range []string{"", "abc", "1.5", "1e3"} {
		t.Run(in, func(t *testing.T) {
			if _, err := ParseInteger([]byte(in)); err == nil {
				t.Errorf("ParseInteger(%q) = nil error, want error", in)
			}
		})
	}
}
