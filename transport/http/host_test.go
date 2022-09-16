package http

import (
	"testing"
)

func TestValidateEndpointHostHandler(t *testing.T) {
	cases := map[string]struct {
		Input string
		Valid bool
	}{
		"valid host":  {Input: "abc.123", Valid: true},
		"fqdn host":   {Input: "abc.123.", Valid: true},
		"empty label": {Input: "abc..", Valid: false},
		"max host len": {
			Input: "123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.12345",
			Valid: true,
		},
		"too long host": {
			Input: "123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456789.123456",
			Valid: false,
		},
		"valid host with port number":         {Input: "abd.123:1234", Valid: true},
		"valid host with invalid port number": {Input: "abc.123:99999", Valid: false},
		"empty host with port number":         {Input: ":1234", Valid: false},
		"valid host with empty port number":   {Input: "abc.123:", Valid: false},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			err := ValidateEndpointHost(c.Input)
			if e, a := c.Valid, err == nil; e != a {
				t.Errorf("expect valid %v, got %v, %v", e, a, err)
			}
		})
	}
}
