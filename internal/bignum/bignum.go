// Package bignum converts between [smithy.BigDecimal] / *big.Int and the
// various wire representations Smithy protocols use for the bigInteger and
// bigDecimal shapes.
//
// bigInteger maps directly to *big.Int, which already has text conversions
// (SetString / Append) built in, so this package's job for integers is
// limited to what those don't cover: none currently, but ParseInteger /
// FormatInteger are kept here as the single call site other codecs use, so a
// shared concern (e.g. a size limit) has one place to live.
//
// bigDecimal maps to [smithy.BigDecimal], which already holds a mantissa and
// exponent, so there's no decomposition step. This package's job for decimals
// is formatting/parsing the plain decimal text that JSON, XML, query, and
// httpbinding all carry it as.
package bignum

import (
	"fmt"
	"math/big"

	"github.com/aws/smithy-go"
)

// maxExponentDigits bounds the exponent accepted from decimal text. An
// exponent is applied by shifting digits, so an absurd one would otherwise
// let a tiny payload demand an enormous allocation.
const maxExponentDigits = 6

// maxDecimalShift bounds how far FormatDecimal will shift the decimal point,
// since each step of shift is a byte of output.
const maxDecimalShift = 1 << 20

// ParseInteger parses decimal integer text into a big.Int.
func ParseInteger(text []byte) (*big.Int, error) {
	v, ok := new(big.Int).SetString(string(text), 10)
	if !ok {
		return nil, fmt.Errorf("malformed decimal integer %q", text)
	}
	return v, nil
}

// FormatInteger renders a big.Int as decimal integer text.
func FormatInteger(v *big.Int) []byte {
	return v.Append(nil, 10)
}

// ParseDecimal parses plain decimal text (with optional exponent notation)
// into a [smithy.BigDecimal].
//
// The parse preserves scale: "1.50" yields Mantissa 150, Exp -2, not Mantissa
// 15, Exp -1. Round-tripping through [FormatDecimal] therefore reproduces the
// original text for any input in plain decimal notation (i.e. without
// exponent notation, which FormatDecimal never emits).
//
// Exponent notation is accepted and folded into the returned exponent, so
// "1.5e-3" yields Mantissa 15, Exp -4.
func ParseDecimal(text []byte) (smithy.BigDecimal, error) {
	s := string(text)
	if len(s) == 0 {
		return smithy.BigDecimal{}, fmt.Errorf("empty decimal number")
	}

	i := 0
	neg := false
	if s[i] == '+' || s[i] == '-' {
		neg = s[i] == '-'
		i++
	}

	var digits []byte
	var fracDigits int
	var sawDigit, sawDot bool

	for ; i < len(s); i++ {
		c := s[i]
		switch {
		case c >= '0' && c <= '9':
			sawDigit = true
			digits = append(digits, c)
			if sawDot {
				fracDigits++
			}
		case c == '.':
			if sawDot {
				return smithy.BigDecimal{}, fmt.Errorf("malformed decimal number %q", s)
			}
			sawDot = true
		case c == 'e' || c == 'E':
			if !sawDigit {
				return smithy.BigDecimal{}, fmt.Errorf("malformed decimal number %q", s)
			}
			exp, perr := parseExponent(s[i+1:])
			if perr != nil {
				return smithy.BigDecimal{}, fmt.Errorf("malformed decimal number %q: %w", s, perr)
			}
			// A positive exponent shifts the point right, reducing scale.
			return smithy.BigDecimal{
				Mantissa: finishMantissa(digits, neg),
				Exp:      exp - int64(fracDigits),
			}, nil
		default:
			return smithy.BigDecimal{}, fmt.Errorf("malformed decimal number %q", s)
		}
	}

	if !sawDigit {
		return smithy.BigDecimal{}, fmt.Errorf("malformed decimal number %q", s)
	}
	return smithy.BigDecimal{
		Mantissa: finishMantissa(digits, neg),
		Exp:      -int64(fracDigits),
	}, nil
}

func finishMantissa(digits []byte, neg bool) *big.Int {
	if len(digits) == 0 {
		digits = []byte{'0'}
	}
	m, _ := new(big.Int).SetString(string(digits), 10)
	if neg {
		m.Neg(m)
	}
	return m
}

func parseExponent(s string) (int64, error) {
	if len(s) == 0 {
		return 0, fmt.Errorf("missing exponent")
	}
	i := 0
	neg := false
	if s[i] == '+' || s[i] == '-' {
		neg = s[i] == '-'
		i++
	}
	if i == len(s) || len(s)-i > maxExponentDigits {
		return 0, fmt.Errorf("exponent out of range")
	}
	var n int64
	for ; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return 0, fmt.Errorf("malformed exponent")
		}
		n = n*10 + int64(s[i]-'0')
	}
	if neg {
		return -n, nil
	}
	return n, nil
}

// FormatDecimal renders a [smithy.BigDecimal] as plain decimal text.
//
// Exponent notation is never emitted, and the scale implied by a negative
// Exp is preserved, so Mantissa 150 with Exp -2 renders as "1.50".
func FormatDecimal(v smithy.BigDecimal) ([]byte, error) {
	if v.Mantissa == nil {
		return nil, fmt.Errorf("bigDecimal has nil Mantissa")
	}
	if v.Exp > maxDecimalShift || v.Exp < -maxDecimalShift {
		return nil, fmt.Errorf("decimal exponent %d out of range", v.Exp)
	}

	digits := v.Mantissa.Append(nil, 10)

	neg := false
	if len(digits) > 0 && digits[0] == '-' {
		neg = true
		digits = digits[1:]
	}

	var out []byte
	switch {
	case v.Exp == 0:
		out = digits

	case v.Exp > 0:
		// shift the point right by appending zeros
		out = make([]byte, 0, len(digits)+int(v.Exp))
		out = append(out, digits...)
		for i := int64(0); i < v.Exp; i++ {
			out = append(out, '0')
		}

	default:
		frac := int(-v.Exp)
		if frac < len(digits) {
			// point falls inside the digits
			out = make([]byte, 0, len(digits)+1)
			out = append(out, digits[:len(digits)-frac]...)
			out = append(out, '.')
			out = append(out, digits[len(digits)-frac:]...)
		} else {
			// point falls left of the digits: 0.00ddd
			out = make([]byte, 0, frac+2)
			out = append(out, '0', '.')
			for i := 0; i < frac-len(digits); i++ {
				out = append(out, '0')
			}
			out = append(out, digits...)
		}
	}

	if neg {
		return append([]byte{'-'}, out...), nil
	}
	return out, nil
}
