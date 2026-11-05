package v4

import (
	"net/http"
	"strings"
	"testing"

	v4 "github.com/aws/smithy-go/aws-http-auth/v4"
)

// TestBuildCanonicalRequest_QueryOrderAfterEncoding asserts that the canonical
// query string is ordered on the URI-encoded form of each parameter. SigV4
// requires each name and value to be URI-encoded individually, and states that
// "the sorting occurs after encoding":
// https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html
func TestBuildCanonicalRequest_QueryOrderAfterEncoding(t *testing.T) {
	for name, tt := range map[string]struct {
		RawQuery string
		Expect   string
	}{
		// '/' (0x2F) sorts after '-' (0x2D), but once encoded '%' (0x25)
		// sorts before it, so the two orders disagree.
		"repeated key, values diverging at '/' and '-'": {
			RawQuery: "k=a-b&k=a%2Fb",
			Expect:   "k=a%2Fb&k=a-b",
		},
		"repeated key, shared prefix": {
			RawQuery: "p=team%2Fgroup&p=team-scout%2Fgroup",
			Expect:   "p=team%2Fgroup&p=team-scout%2Fgroup",
		},
		// Key axis: 'ሴ' encodes to %E1%88%B4, and '%' sorts before 'P'.
		"keys ordered on encoded form": {
			RawQuery: "Param-3=Value3&Param=Value2&%E1%88%B4=Value1",
			Expect:   "%E1%88%B4=Value1&Param=Value2&Param-3=Value3",
		},
		// Controls: these order identically either way.
		"values already in order": {
			RawQuery: "k=alpha&k=zeta",
			Expect:   "k=alpha&k=zeta",
		},
		"repeated values still sorted within a key": {
			RawQuery: "b=1&a=2&a=1",
			Expect:   "a=1&a=2&b=1",
		},
		"space encodes to %20": {
			RawQuery: "k=a+b",
			Expect:   "k=a%20b",
		},
	} {
		t.Run(name, func(t *testing.T) {
			req, err := http.NewRequest(http.MethodGet,
				"https://service.region.amazonaws.com", nil)
			if err != nil {
				t.Fatal(err)
			}
			req.URL.Path = "/"
			req.URL.RawQuery = tt.RawQuery
			req.Header.Set("Host", "service.region.amazonaws.com")

			s := &Signer{
				Request:     req,
				PayloadHash: v4.UnsignedPayload(),
				Options: v4.SignerOptions{
					HeaderRules: defaultHeaderRules{},
				},
			}

			canon, _ := s.buildCanonicalRequest()
			lines := strings.Split(canon, "\n")
			if len(lines) < 3 {
				t.Fatalf("malformed canonical request:\n%s", canon)
			}
			if tt.Expect != lines[2] {
				t.Errorf("canonical query\n  expect %s\n  actual %s", tt.Expect, lines[2])
			}
		})
	}
}
