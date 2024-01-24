package cbor

import (
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
