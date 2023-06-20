package http

import "testing"

func TestUserAgentBuilder(t *testing.T) {
	cases := map[string]struct {
		Key       string
		Value     string
		SecondKey string
		Expect    string
	}{
		"Single key": {
			Key:    "baz",
			Expect: "baz",
		},
		"Single key and value with allowed char": {
			Key:    "baz/ba",
			Value:  "a+b-C$4'5.6",
			Expect: "baz/ba#a+b-C$4'5.6",
		},
		"Tow keys": {
			Key:       "baz/ba",
			Value:     "1+2-3",
			SecondKey: "zab",
			Expect:    "baz/ba#1+2-3 zab",
		},
		"Value needs sanitizing": {
			Key:       "baz/ba",
			Value:     "1(2)3",
			SecondKey: "zab",
			Expect:    "baz/ba#1-2-3 zab",
		},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			b := NewUserAgentBuilder()
			if len(c.Value) > 0 {
				b.AddKeyValue(c.Key, c.Value)
			} else {
				b.AddKey(c.Key)
			}
			if len(c.SecondKey) > 0 {
				b.AddKey(c.SecondKey)
			}
			if e, a := c.Expect, b.Build(); e != a {
				t.Errorf("expect %v, got %v", e, a)
			}
		})
	}
}
