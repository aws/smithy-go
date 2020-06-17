package testing

import (
	"testing"
)

func TestAssertJSON(t *testing.T) {
	cases := map[string]struct {
		X, Y  []byte
		Equal bool
	}{
		"equal": {
			X:     []byte(`{"RecursiveStruct":{"RecursiveMap":{"foo":{"NoRecurse":"foo"},"bar":{"NoRecurse":"bar"}}}}`),
			Y:     []byte(`{"RecursiveStruct":{"RecursiveMap":{"bar":{"NoRecurse":"bar"},"foo":{"NoRecurse":"foo"}}}}`),
			Equal: true,
		},
		"not equal": {
			X:     []byte(`{"RecursiveStruct":{"RecursiveMap":{"foo":{"NoRecurse":"foo"},"bar":{"NoRecurse":"bar"}}}}`),
			Y:     []byte(`{"RecursiveStruct":{"RecursiveMap":{"foo":{"NoRecurse":"foo"}}}}`),
			Equal: false,
		},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			err := JSONEqual(c.X, c.Y)
			if c.Equal {
				if err != nil {
					t.Fatalf("expect JSON to be equal, %v", err)
				}
			} else if err == nil {
				t.Fatalf("expect JSON to be equal, %v", err)
			}
		})
	}
}
