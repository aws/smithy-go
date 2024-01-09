package cbor

import (
	"bytes"
	"encoding/hex"
	"reflect"
	"testing"
)

func TestDecode_scratch(t *testing.T) {
	encoded := mkex("A363666F6F636261726362617A81BF637175789F63666F6F7F63626172FFFF63666F6F0163626172D73901F3FF637175785F41FF4300B0ACFF")
	e := Map{
		"foo": String("bar"),
		"baz": List{
			Map{
				"qux": List{String("foo"), String("bar")},
				"foo": Uint(1),
				"bar": &Tag{
					ID:    23,
					Value: NegInt(500),
				},
			},
		},
		"qux": Slice{0xff, 0x0, 0xb0, 0xac},
	}

	a, err := Decode(encoded)
	if err != nil {
		t.Fatal(err)
	}

	if !reflect.DeepEqual(e, a) {
		t.Fatal(e, a)
	}
}

func assertEqInt(t *testing.T, e, a int, msg string) {
	if e != a {
		t.Errorf("%s: %d != %d", msg, e, a)
	}
}

func assertEq(t *testing.T, e, a []byte, msg string) {
	if !bytes.Equal(e, a) {
		t.Errorf("%s: %c != %c (len %d, %d)", msg, e, a, len(e), len(a))
	}
}

func mkex(ex string) []byte {
	p, _ := hex.DecodeString(ex)
	return p
}
