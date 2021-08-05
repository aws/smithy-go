package uuid

import "encoding/hex"

const dash byte = '-'

// Format returns the canonical text representation of a UUID.
// This implementation is optimized to not use fmt.
// Example: 82e42f16-b6cc-4d5b-95f5-d403c4befd3d
func Format(u [16]byte) string {
	// https://en.wikipedia.org/wiki/Universally_unique_identifier#Version_4_.28random.29

	var scratch [36]byte

	hex.Encode(scratch[:8], u[0:4])
	scratch[8] = dash
	hex.Encode(scratch[9:13], u[4:6])
	scratch[13] = dash
	hex.Encode(scratch[14:18], u[6:8])
	scratch[18] = dash
	hex.Encode(scratch[19:23], u[8:10])
	scratch[23] = dash
	hex.Encode(scratch[24:], u[10:])

	return string(scratch[:])
}
