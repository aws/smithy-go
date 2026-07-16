package sigv4

import (
	"net/http"
	"net/url"
	"strings"
	"testing"

	"github.com/aws/smithy-go/aws-http-auth/credentials"
)

// A request built outside http.NewRequest (as in the smithy-go pipeline) has URL.Host set
// but Request.Host empty. net/http sends URL.Host on the wire, so the signature must be over
// URL.Host, not an empty host.
func TestSignRequestHostFromURLWhenHostEmpty(t *testing.T) {
	u, err := url.Parse("https://api.example.com/v2/events/abc")
	if err != nil {
		t.Fatal(err)
	}
	req := &http.Request{
		Method: http.MethodGet,
		URL:    u,
		Header: http.Header{},
		// Host intentionally left empty.
	}

	s := New()
	if err := s.SignRequest(&SignRequestInput{
		Request:     req,
		Credentials: credentials.Credentials{AccessKeyID: "AKIDEXAMPLE", SecretAccessKey: "SECRET"},
		Service:     "execute-api",
		Region:      "us-east-1",
	}); err != nil {
		t.Fatalf("SignRequest: %v", err)
	}

	if got := req.Header.Get("Host"); got != "api.example.com" {
		t.Fatalf("signed Host header = %q, want api.example.com", got)
	}
	if auth := req.Header.Get("Authorization"); !strings.Contains(auth, "SignedHeaders=host;") {
		t.Fatalf("expected host in SignedHeaders, got %q", auth)
	}
}
