package smithy

import "math/big"

// BigDecimal is the Smithy bigDecimal type: an arbitrary precision signed
// decimal number, held as an arbitrarily large mantissa and a base-10
// exponent such that the value equals Mantissa * 10**Exp.
//
// The zero value has a nil Mantissa and is not a valid number. Construct one
// explicitly, e.g.:
//
//	v := smithy.BigDecimal{Mantissa: big.NewInt(15), Exp: -1} // 1.5
type BigDecimal struct {
	Mantissa *big.Int
	Exp      int64
}
