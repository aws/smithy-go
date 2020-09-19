package http

import (
	"bytes"
	"io"
	"net/http"
	"net/url"
	"strings"
	"testing"
)

func TestRequestRewindable(t *testing.T) {
	cases := map[string]struct {
		Stream    io.Reader
		ExpectErr string
	}{
		"rewindable": {
			Stream: bytes.NewReader([]byte{}),
		},
		"not rewindable": {
			Stream:    bytes.NewBuffer([]byte{}),
			ExpectErr: "stream is not seekable",
		},
		"nil stream": {},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			req := &Request{
				Request: &http.Request{
					URL:    &url.URL{},
					Header: http.Header{},
				},
			}

			req, err := req.SetStream(c.Stream)
			if err != nil {
				t.Fatalf("expect no error setting stream, %v", err)
			}

			err = req.RewindStream()
			if len(c.ExpectErr) != 0 {
				if err == nil {
					t.Fatalf("expect error, got none")
				}
				if e, a := c.ExpectErr, err.Error(); !strings.Contains(a, e) {
					t.Fatalf("expect error to contain %v, got %v", e, a)
				}
				return
			}
			if err != nil {
				t.Fatalf("expect no error, got %v", err)
			}
		})
	}
}
