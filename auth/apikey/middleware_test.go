package apikey

import (
	"context"
	"net/http"
	"net/url"
	"strings"
	"testing"

	smithyhttp "github.com/aws/smithy-go/transport/http"
	"github.com/google/go-cmp/cmp"
	"github.com/google/go-cmp/cmp/cmpopts"
)

func TestApiKeyMiddleware(t *testing.T) {
	cases := map[string]struct {
		message        Message
		apiKey         string
		authDefinition *HttpApiKeyAuthDefinition
		expectMessage  Message
		expectErr      string
	}{
		// Cases
		"not smithyhttp.Request": {
			message:   struct{}{},
			expectErr: "expect smithy-go HTTP Request",
		},
		"invalid http auth": {
			message: func() Message {
				r := smithyhttp.NewStackRequest().(*smithyhttp.Request)
				r.URL, _ = url.Parse("http://example.aws")
				return r
			}(),
			authDefinition: &HttpApiKeyAuthDefinition{
				In:   "invalid",
				Name: "invalid",
			},
			expectErr: "invalid HTTP auth",
		},
		"success on header": {
			message: func() Message {
				r := smithyhttp.NewStackRequest().(*smithyhttp.Request)
				r.URL, _ = url.Parse("https://example.aws")
				return r
			}(),
			apiKey: "abc123",
			authDefinition: &HttpApiKeyAuthDefinition{
				In:     "header",
				Name:   "Authorization",
				Scheme: "Apikey",
			},
			expectMessage: func() Message {
				r := smithyhttp.NewStackRequest().(*smithyhttp.Request)
				r.URL, _ = url.Parse("https://example.aws")
				r.Header.Set("Authorization", "Apikey abc123")
				return r
			}(),
		},
		"success on query": {
			message: func() Message {
				r := smithyhttp.NewStackRequest().(*smithyhttp.Request)
				r.URL, _ = url.Parse("https://example.aws")
				return r
			}(),
			apiKey: "abc123",
			authDefinition: &HttpApiKeyAuthDefinition{
				In:   "query",
				Name: "api_key",
			},
			expectMessage: func() Message {
				r := smithyhttp.NewStackRequest().(*smithyhttp.Request)
				r.URL, _ = url.Parse("https://example.aws?api_key=abc123")
				return r
			}(),
		},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			ctx := context.Background()
			signer := SignMessage{}
			message, err := signer.SignWithApiKey(ctx, c.apiKey, c.authDefinition, c.message)
			if c.expectErr != "" {
				if err == nil {
					t.Fatalf("expect error, got none")
				}
				if e, a := c.expectErr, err.Error(); !strings.Contains(a, e) {
					t.Fatalf("expect %v in error %v", e, a)
				}
				return
			} else if err != nil {
				t.Fatalf("expect no error, got %v", err)
			}

			options := []cmp.Option{
				cmpopts.IgnoreUnexported(smithyhttp.Request{}),
				cmpopts.IgnoreUnexported(http.Request{}),
			}

			if diff := cmp.Diff(c.expectMessage, message, options...); diff != "" {
				t.Errorf("expect match\n%s", diff)
			}
		})
	}
}
