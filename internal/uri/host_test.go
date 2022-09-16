package uri

import (
	"strconv"
	"testing"
)

func TestValidPortNumber(t *testing.T) {
	cases := []struct {
		Input string
		Valid bool
	}{
		{Input: "123", Valid: true},
		{Input: "123.0", Valid: false},
		{Input: "-123", Valid: false},
		{Input: "65536", Valid: false},
		{Input: "0", Valid: true},
	}
	for i, c := range cases {
		t.Run(strconv.Itoa(i), func(t *testing.T) {
			valid := ValidPortNumber(c.Input)
			if e, a := c.Valid, valid; e != a {
				t.Errorf("expect valid %v, got %v", e, a)
			}
		})
	}

}

func TestValidHostLabel(t *testing.T) {
	cases := []struct {
		Input string
		Valid bool
	}{
		{Input: "abc123", Valid: true},
		{Input: "123", Valid: true},
		{Input: "abc", Valid: true},
		{Input: "123-abc", Valid: true},
		{Input: "-123-abc", Valid: false},
		{Input: "{thing}-abc", Valid: false},
		{Input: "abc.123", Valid: false},
		{Input: "abc/123", Valid: false},
		{Input: "012345678901234567890123456789012345678901234567890123456789123", Valid: true},
		{Input: "0123456789012345678901234567890123456789012345678901234567891234", Valid: false},
		{Input: "", Valid: false},
	}

	for i, c := range cases {
		t.Run(strconv.Itoa(i), func(t *testing.T) {
			valid := ValidHostLabel(c.Input)
			if e, a := c.Valid, valid; e != a {
				t.Errorf("expect valid %v, got %v", e, a)
			}
		})
	}
}
