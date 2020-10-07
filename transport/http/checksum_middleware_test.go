package http

import (
	"bytes"
	"context"
	"io"
	"io/ioutil"
	"strings"
	"testing"

	"github.com/awslabs/smithy-go/middleware"
)

func TestChecksumMiddleware(t *testing.T) {
	cases := map[string]struct {
		requestBody         io.ReadCloser
		expectedMD5Checksum string
		expectError         string
	}{
		"empty body": {
			requestBody:         ioutil.NopCloser(bytes.NewBuffer([]byte(``))),
			expectedMD5Checksum: "1B2M2Y8AsgTpgAmY7PhCfg==",
		},
		"standard case": {
			requestBody:         ioutil.NopCloser(bytes.NewBuffer([]byte(`abc`))),
			expectedMD5Checksum: "kAFQmDzST7DWlj99KOF/cg==",
		},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			var err error
			req := NewStackRequest().(*Request)
			req.Body = c.requestBody
			m := checksumMiddleware{}
			_, _, err = m.HandleBuild(context.Background(),
				middleware.BuildInput{Request: req},
				nopBuildHandler,
			)

			if len(c.expectError) != 0 {
				if err == nil {
					t.Fatalf("expect error, got none")
				}
				if e, a := c.expectError, err.Error(); !strings.Contains(a, e) {
					t.Fatalf("expect error to contain %q, got %v", e, a)
				}
			} else if err != nil {
				t.Fatalf("expect no error, got %v", err)
			}

			if e, a := c.expectedMD5Checksum, req.Header.Get(contentMD5Header); e != a {
				t.Errorf("expect md5 checksum : %v, got %v", e, a)
			}
		})
	}
}
