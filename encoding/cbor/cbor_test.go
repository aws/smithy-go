package cbor

import (
	"bytes"
	"encoding/hex"
	"math"
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

	assertValue(t, e, a)
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

func assertValue(t *testing.T, e, a Value) {
	switch v := e.(type) {
	case Uint, NegInt, Slice, String, Major7Bool, *Major7Nil, *Major7Undefined:
		if !reflect.DeepEqual(e, a) {
			t.Errorf("%v != %v", e, a)
		}
	case List:
		assertList(t, v, a)
	case Map:
		assertMap(t, v, a)
	case *Tag:
		assertTag(t, v, a)
	case Major7Float32:
		assertMajor7Float32(t, v, a)
	case Major7Float64:
		assertMajor7Float64(t, v, a)
	default:
		t.Errorf("unrecognized variant %T", e)
	}
}

func assertList(t *testing.T, e List, a Value) {
	av, ok := a.(List)
	if !ok {
		t.Errorf("%T != %T", e, a)
		return
	}

	if len(e) != len(av) {
		t.Errorf("length %d != %d", len(e), len(av))
		return
	}

	for i := 0; i < len(e); i++ {
		assertValue(t, e[i], av[i])
	}
}

func assertMap(t *testing.T, e Map, a Value) {
	av, ok := a.(Map)
	if !ok {
		t.Errorf("%T != %T", e, a)
		return
	}

	if len(e) != len(av) {
		t.Errorf("length %d != %d", len(e), len(av))
		return
	}

	for k, ev := range e {
		avv, ok := av[k]
		if !ok {
			t.Errorf("missing key %s", k)
			return
		}

		assertValue(t, ev, avv)
	}
}

func assertTag(t *testing.T, e *Tag, a Value) {
	av, ok := a.(*Tag)
	if !ok {
		t.Errorf("%T != %T", e, a)
		return
	}

	if e.ID != av.ID {
		t.Errorf("tag ID %d != %d", e.ID, av.ID)
		return
	}

	assertValue(t, e.Value, av.Value)
}

func assertMajor7Float32(t *testing.T, e Major7Float32, a Value) {
	av, ok := a.(Major7Float32)
	if !ok {
		t.Errorf("%T != %T", e, a)
		return
	}

	if math.Float32bits(float32(e)) != math.Float32bits(float32(av)) {
		t.Errorf("float32(%x) != float32(%x)", e, av)
	}
}

func assertMajor7Float64(t *testing.T, e Major7Float64, a Value) {
	av, ok := a.(Major7Float64)
	if !ok {
		t.Errorf("%T != %T", e, a)
		return
	}

	if math.Float64bits(float64(e)) != math.Float64bits(float64(av)) {
		t.Errorf("float64(%x) != float64(%x)", e, av)
	}
}
