package rulesfn

import (
	"strings"
	"testing"
	smithyerrep "github.com/aws/smithy-go/error/endpoints"
	"github.com/google/go-cmp/cmp"
)

func TestURIEncode(t *testing.T) {
	cases := map[string]struct {
		input  string
		expect string
	}{
		"no encoding": {
			input:  "a-zA-Z0-9-_.~",
			expect: "a-zA-Z0-9-_.~",
		},
		"with encoding": {
			input:  "🐛 becomes 🦋",
			expect: "%F0%9F%90%9B%20becomes%20%F0%9F%A6%8B",
		},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			ec := smithyerrep.NewErrorCollector()
			actual := URIEncode(c.input, ec)
			if e, a := c.expect, actual; e != a {
				t.Errorf("expect `%v` encoding, got `%v`", e, a)
			}
			if !ec.IsEmpty() {
				t.Errorf("expect error collector empty, got %v", ec)
			}
		})
	}
}

func TestParseURL(t *testing.T) {
	cases := map[string]struct {
		input     string
		expect    *URL
		expectErr string
	}{
		"https hostname with no path": {
			input: "https://example.com",
			expect: &URL{
				Scheme:         "https",
				Authority:      "example.com",
				Path:           "",
				NormalizedPath: "/",
			},
		},
		"http hostname with no path": {
			input: "http://example.com",
			expect: &URL{
				Scheme:         "http",
				Authority:      "example.com",
				Path:           "",
				NormalizedPath: "/",
			},
		},
		"https hostname with port with path": {
			input: "https://example.com:80/foo/bar",
			expect: &URL{
				Scheme:         "https",
				Authority:      "example.com:80",
				Path:           "/foo/bar",
				NormalizedPath: "/foo/bar/",
			},
		},
		"invalid port": {
			input:     "https://example.com:abc",
			expectErr: "invalid port \":abc\"",
		},
		"with query": {
			input:     "https://example.com:8443?foo=bar&faz=baz",
			expectErr: "URL must not include query string",
		},
		"ip4 URL": {
			input: "https://127.0.0.1",
			expect: &URL{
				Scheme:         "https",
				Authority:      "127.0.0.1",
				Path:           "",
				NormalizedPath: "/",
				IsIp:           true,
			},
		},
		"ip4 URL with port": {
			input: "https://127.0.0.1:8443",
			expect: &URL{
				Scheme:         "https",
				Authority:      "127.0.0.1:8443",
				Path:           "",
				NormalizedPath: "/",
				IsIp:           true,
			},
		},
		"ip6 short": {
			input: "https://[fe80::1]",
			expect: &URL{
				Scheme:         "https",
				Authority:      "[fe80::1]",
				Path:           "",
				NormalizedPath: "/",
				IsIp:           true,
			},
		},
		"ip6 short with interface": {
			input: "https://[fe80::1%25en0]",
			expect: &URL{
				Scheme:         "https",
				Authority:      "[fe80::1%25en0]",
				Path:           "",
				NormalizedPath: "/",
				IsIp:           true,
			},
		},
		"ip6 short with port": {
			input: "https://[fe80::1]:8443",
			expect: &URL{
				Scheme:         "https",
				Authority:      "[fe80::1]:8443",
				Path:           "",
				NormalizedPath: "/",
				IsIp:           true,
			},
		},
		"ip6 short with port with interface": {
			input: "https://[fe80::1%25en0]:8443",
			expect: &URL{
				Scheme:         "https",
				Authority:      "[fe80::1%25en0]:8443",
				Path:           "",
				NormalizedPath: "/",
				IsIp:           true,
			},
		},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			ec := smithyerrep.NewErrorCollector()
			actual := ParseURL(c.input, ec)
			if c.expect == nil {
				if actual != nil {
					t.Fatalf("expect no result, got %v", *actual)
				}
				if e, a := c.expectErr, ec.Error(); !strings.Contains(a, e) {
					t.Errorf("expect %q error in %q", e, a)
				}
				return
			}

			if actual == nil {
				t.Fatalf("expect result, got none")
			}

			if diff := cmp.Diff(c.expect, actual); diff != "" {
				t.Errorf("expect URL to match\n%s", diff)
			}
		})
	}
}

func TestIsValidHostLabel(t *testing.T) {
	cases := map[string]struct {
		input           string
		allowSubDomains bool
		expect          bool
		expectErr       string
	}{
		"single label no split": {
			input:  "abc123-",
			expect: true,
		},
		"single label with split": {
			input:           "abc123-",
			allowSubDomains: true,
			expect:          true,
		},
		"multiple labels no split": {
			input:     "abc.123-",
			expectErr: `host label 0 is invalid, "abc.123-"`,
		},
		"multiple labels with split": {
			input:           "abc.123-",
			allowSubDomains: true,
			expect:          true,
		},
		"multiple labels with split invalid label": {
			input:           "abc.123-...",
			allowSubDomains: true,
			expectErr:       `host label 2 is invalid, ""`,
		},
		"max length host label": {
			input:  "012345678901234567890123456789012345678901234567890123456789123",
			expect: true,
		},
		"too large host label": {
			input:     "0123456789012345678901234567890123456789012345678901234567891234",
			expectErr: `host label 0 is invalid, "0123456789012345678901234567890123456789012345678901234567891234"`,
		},
		"too small host label": {
			input:     "",
			expectErr: `host label 0 is invalid, ""`,
		},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			ec := smithyerrep.NewErrorCollector()
			actual := IsValidHostLabel(c.input, c.allowSubDomains, ec)
			if !c.expect {
				if e, a := c.expectErr, ec.Error(); !strings.Contains(a, e) {
					t.Errorf("expect %q error in %q", e, a)
				}
			}

			if e, a := c.expect, actual; e != a {
				t.Fatalf("expect %v valid host label, got %v", e, a)
			}
		})
	}
}
