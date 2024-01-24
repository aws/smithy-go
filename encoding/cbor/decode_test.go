package cbor

import (
	"math"
	"reflect"
	"strings"
	"testing"
)

func TestDecode_InvalidArgument(t *testing.T) {
	for name, c := range map[string]struct {
		In  []byte
		Err string
	}{
		"uint/1": {
			[]byte{0<<5 | 24},
			"arg len 1 greater than remaining buf len",
		},
		"uint/2": {
			[]byte{0<<5 | 25, 0},
			"arg len 2 greater than remaining buf len",
		},
		"uint/4": {
			[]byte{0<<5 | 26, 0, 0, 0},
			"arg len 4 greater than remaining buf len",
		},
		"uint/8": {
			[]byte{0<<5 | 27, 0, 0, 0, 0, 0, 0, 0},
			"arg len 8 greater than remaining buf len",
		},
		"uint/?": {
			[]byte{0<<5 | 31},
			"unexpected minor value 31",
		},
		"negint/1": {
			[]byte{1<<5 | 24},
			"arg len 1 greater than remaining buf len",
		},
		"negint/2": {
			[]byte{1<<5 | 25, 0},
			"arg len 2 greater than remaining buf len",
		},
		"negint/4": {
			[]byte{1<<5 | 26, 0, 0, 0},
			"arg len 4 greater than remaining buf len",
		},
		"negint/8": {
			[]byte{1<<5 | 27, 0, 0, 0, 0, 0, 0, 0},
			"arg len 8 greater than remaining buf len",
		},
		"negint/?": {
			[]byte{1<<5 | 31},
			"unexpected minor value 31",
		},
		"slice/1": {
			[]byte{2<<5 | 24},
			"arg len 1 greater than remaining buf len",
		},
		"slice/2": {
			[]byte{2<<5 | 25, 0},
			"arg len 2 greater than remaining buf len",
		},
		"slice/4": {
			[]byte{2<<5 | 26, 0, 0, 0},
			"arg len 4 greater than remaining buf len",
		},
		"slice/8": {
			[]byte{2<<5 | 27, 0, 0, 0, 0, 0, 0, 0},
			"arg len 8 greater than remaining buf len",
		},
		"string/1": {
			[]byte{3<<5 | 24},
			"arg len 1 greater than remaining buf len",
		},
		"string/2": {
			[]byte{3<<5 | 25, 0},
			"arg len 2 greater than remaining buf len",
		},
		"string/4": {
			[]byte{3<<5 | 26, 0, 0, 0},
			"arg len 4 greater than remaining buf len",
		},
		"string/8": {
			[]byte{3<<5 | 27, 0, 0, 0, 0, 0, 0, 0},
			"arg len 8 greater than remaining buf len",
		},
		"list/1": {
			[]byte{4<<5 | 24},
			"arg len 1 greater than remaining buf len",
		},
		"list/2": {
			[]byte{4<<5 | 25, 0},
			"arg len 2 greater than remaining buf len",
		},
		"list/4": {
			[]byte{4<<5 | 26, 0, 0, 0},
			"arg len 4 greater than remaining buf len",
		},
		"list/8": {
			[]byte{4<<5 | 27, 0, 0, 0, 0, 0, 0, 0},
			"arg len 8 greater than remaining buf len",
		},
		"map/1": {
			[]byte{5<<5 | 24},
			"arg len 1 greater than remaining buf len",
		},
		"map/2": {
			[]byte{5<<5 | 25, 0},
			"arg len 2 greater than remaining buf len",
		},
		"map/4": {
			[]byte{5<<5 | 26, 0, 0, 0},
			"arg len 4 greater than remaining buf len",
		},
		"map/8": {
			[]byte{5<<5 | 27, 0, 0, 0, 0, 0, 0, 0},
			"arg len 8 greater than remaining buf len",
		},
		"tag/1": {
			[]byte{6<<5 | 24},
			"arg len 1 greater than remaining buf len",
		},
		"tag/2": {
			[]byte{6<<5 | 25, 0},
			"arg len 2 greater than remaining buf len",
		},
		"tag/4": {
			[]byte{6<<5 | 26, 0, 0, 0},
			"arg len 4 greater than remaining buf len",
		},
		"tag/8": {
			[]byte{6<<5 | 27, 0, 0, 0, 0, 0, 0, 0},
			"arg len 8 greater than remaining buf len",
		},
		"tag/?": {
			[]byte{6<<5 | 31},
			"unexpected minor value 31",
		},
		"major7/float16": {
			[]byte{7<<5 | 25, 0},
			"incomplete float16 at end of buf",
		},
		"major7/float32": {
			[]byte{7<<5 | 26, 0, 0, 0},
			"incomplete float32 at end of buf",
		},
		"major7/float64": {
			[]byte{7<<5 | 27, 0, 0, 0, 0, 0, 0, 0},
			"incomplete float64 at end of buf",
		},
		"major7/?": {
			[]byte{7<<5 | 31},
			"unexpected minor value 31",
		},
	} {
		t.Run(name, func(t *testing.T) {
			_, _, err := decode(c.In)
			if err == nil {
				t.Errorf("expect err %s", c.Err)
			}
			if aerr := err.Error(); !strings.Contains(aerr, c.Err) {
				t.Errorf("expect err %s, got %s", c.Err, aerr)
			}
		})
	}
}

func TestDecode_InvalidSlice(t *testing.T) {
	for name, c := range map[string]struct {
		In  []byte
		Err string
	}{
		"slice/1, not enough bytes": {
			[]byte{2<<5 | 24, 1},
			"slice len 1 greater than remaining buf len",
		},
		"slice/?, no break": {
			[]byte{2<<5 | 31},
			"expected break marker",
		},
		"slice/?, invalid nested major": {
			[]byte{2<<5 | 31, 3<<5 | 0},
			"unexpected major type 3 in indefinite slice",
		},
		"slice/?, nested indefinite": {
			[]byte{2<<5 | 31, 2<<5 | 31},
			"nested indefinite slice",
		},
		"slice/?, invalid nested definite": {
			[]byte{2<<5 | 31, 2<<5 | 24, 1},
			"decode subslice: slice len 1 greater than remaining buf len",
		},
		"string/1, not enough bytes": {
			[]byte{3<<5 | 24, 1},
			"slice len 1 greater than remaining buf len",
		},
		"string/?, no break": {
			[]byte{3<<5 | 31},
			"expected break marker",
		},
		"string/?, invalid nested major": {
			[]byte{3<<5 | 31, 2<<5 | 0},
			"unexpected major type 2 in indefinite slice",
		},
		"string/?, nested indefinite": {
			[]byte{3<<5 | 31, 3<<5 | 31},
			"nested indefinite slice",
		},
		"string/?, invalid nested definite": {
			[]byte{3<<5 | 31, 3<<5 | 24, 1},
			"decode subslice: slice len 1 greater than remaining buf len",
		},
	} {
		t.Run(name, func(t *testing.T) {
			_, _, err := decode(c.In)
			if err == nil {
				t.Errorf("expect err %s", c.Err)
			}
			if aerr := err.Error(); !strings.Contains(aerr, c.Err) {
				t.Errorf("expect err %s, got %s", c.Err, aerr)
			}
		})
	}
}

func TestDecode_InvalidList(t *testing.T) {
	for name, c := range map[string]struct {
		In  []byte
		Err string
	}{
		"[] / eof after head": {
			[]byte{4<<5 | 1},
			"unexpected end of payload",
		},
		"[] / invalid item": {
			[]byte{4<<5 | 1, 0<<5 | 24},
			"arg len 1 greater than remaining buf len",
		},
		"[_ ] / no break": {
			[]byte{4<<5 | 31},
			"expected break marker",
		},
		"[_ ] / invalid item": {
			[]byte{4<<5 | 31, 0<<5 | 24},
			"arg len 1 greater than remaining buf len",
		},
	} {
		t.Run(name, func(t *testing.T) {
			_, _, err := decode(c.In)
			if err == nil {
				t.Errorf("expect err %s", c.Err)
			}
			if aerr := err.Error(); !strings.Contains(aerr, c.Err) {
				t.Errorf("expect err %s, got %s", c.Err, aerr)
			}
		})
	}
}

func TestDecode_InvalidMap(t *testing.T) {
	for name, c := range map[string]struct {
		In  []byte
		Err string
	}{
		"{} / eof after head": {
			[]byte{5<<5 | 1},
			"unexpected end of payload",
		},
		"{} / non-string key": {
			[]byte{5<<5 | 1, 0},
			"unexpected major type 0 for map key",
		},
		"{} / invalid key": {
			[]byte{5<<5 | 1, 3<<5 | 24, 1},
			"slice len 1 greater than remaining buf len",
		},
		"{} / invalid value": {
			[]byte{5<<5 | 1, 3<<5 | 3, 0x66, 0x6f, 0x6f, 0<<5 | 24},
			"arg len 1 greater than remaining buf len",
		},
		"{_ } / no break": {
			[]byte{5<<5 | 31},
			"expected break marker",
		},
		"{_ } / non-string key": {
			[]byte{5<<5 | 31, 0},
			"unexpected major type 0 for map key",
		},
		"{_ } / invalid key": {
			[]byte{5<<5 | 31, 3<<5 | 24, 1},
			"slice len 1 greater than remaining buf len",
		},
		"{_ } / invalid value": {
			[]byte{5<<5 | 31, 3<<5 | 3, 0x66, 0x6f, 0x6f, 0<<5 | 24},
			"arg len 1 greater than remaining buf len",
		},
	} {
		t.Run(name, func(t *testing.T) {
			_, _, err := decode(c.In)
			if err == nil {
				t.Errorf("expect err %s", c.Err)
			}
			if aerr := err.Error(); !strings.Contains(aerr, c.Err) {
				t.Errorf("expect err %s, got %s", c.Err, aerr)
			}
		})
	}
}

func TestDecode_InvalidTag(t *testing.T) {
	for name, c := range map[string]struct {
		In  []byte
		Err string
	}{
		"invalid value": {
			[]byte{6<<5 | 1, 0<<5 | 24},
			"arg len 1 greater than remaining buf len",
		},
		"eof": {
			[]byte{6<<5 | 1},
			"unexpected end of payload",
		},
	} {
		t.Run(name, func(t *testing.T) {
			_, _, err := decode(c.In)
			if err == nil {
				t.Errorf("expect err %s", c.Err)
			}
			if aerr := err.Error(); !strings.Contains(aerr, c.Err) {
				t.Errorf("expect err %s, got %s", c.Err, aerr)
			}
		})
	}
}

func TestDecode_Atomic(t *testing.T) {
	for name, c := range map[string]struct {
		In     []byte
		Expect Value
	}{
		"uint/0/min": {
			[]byte{0<<5 | 0},
			Uint(0),
		},
		"uint/0/max": {
			[]byte{0<<5 | 23},
			Uint(23),
		},
		"uint/1/min": {
			[]byte{0<<5 | 24, 0},
			Uint(0),
		},
		"uint/1/max": {
			[]byte{0<<5 | 24, 0xff},
			Uint(0xff),
		},
		"uint/2/min": {
			[]byte{0<<5 | 25, 0, 0},
			Uint(0),
		},
		"uint/2/max": {
			[]byte{0<<5 | 25, 0xff, 0xff},
			Uint(0xffff),
		},
		"uint/4/min": {
			[]byte{0<<5 | 26, 0, 0, 0, 0},
			Uint(0),
		},
		"uint/4/max": {
			[]byte{0<<5 | 26, 0xff, 0xff, 0xff, 0xff},
			Uint(0xffffffff),
		},
		"uint/8/min": {
			[]byte{0<<5 | 27, 0, 0, 0, 0, 0, 0, 0, 0},
			Uint(0),
		},
		"uint/8/max": {
			[]byte{0<<5 | 27, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff},
			Uint(0xffffffff_ffffffff),
		},
		"negint/0/min": {
			[]byte{1<<5 | 0},
			NegInt(1),
		},
		"negint/0/max": {
			[]byte{1<<5 | 23},
			NegInt(24),
		},
		"negint/1/min": {
			[]byte{1<<5 | 24, 0},
			NegInt(1),
		},
		"negint/1/max": {
			[]byte{1<<5 | 24, 0xff},
			NegInt(0x100),
		},
		"negint/2/min": {
			[]byte{1<<5 | 25, 0, 0},
			NegInt(1),
		},
		"negint/2/max": {
			[]byte{1<<5 | 25, 0xff, 0xff},
			NegInt(0x10000),
		},
		"negint/4/min": {
			[]byte{1<<5 | 26, 0, 0, 0, 0},
			NegInt(1),
		},
		"negint/4/max": {
			[]byte{1<<5 | 26, 0xff, 0xff, 0xff, 0xff},
			NegInt(0x100000000),
		},
		"negint/8/min": {
			[]byte{1<<5 | 27, 0, 0, 0, 0, 0, 0, 0, 0},
			NegInt(1),
		},
		"negint/8/max": {
			[]byte{1<<5 | 27, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xfe},
			NegInt(0xffffffff_ffffffff),
		},
		"true": {
			[]byte{7<<5 | major7True},
			Major7Bool(true),
		},
		"false": {
			[]byte{7<<5 | major7False},
			Major7Bool(false),
		},
		"null": {
			[]byte{7<<5 | major7Nil},
			&Major7Nil{},
		},
		"undefined": {
			[]byte{7<<5 | major7Undefined},
			&Major7Undefined{},
		},
	} {
		t.Run(name, func(t *testing.T) {
			actual, n, err := decode(c.In)
			if err != nil {
				t.Errorf("expect no err, got %v", err)
			}
			if n != len(c.In) {
				t.Errorf("didn't decode whole buffer")
			}
			assertValue(t, c.Expect, actual)
		})
	}
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
