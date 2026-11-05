package v4

import (
	"bytes"
	"crypto/sha256"
	"fmt"
	"io"
	"net/url"
	"sort"
	"strings"
)

var noEscape [256]bool

func init() {
	for i := 0; i < len(noEscape); i++ {
		// AWS expects every character except these to be escaped
		noEscape[i] = (i >= 'A' && i <= 'Z') ||
			(i >= 'a' && i <= 'z') ||
			(i >= '0' && i <= '9') ||
			i == '-' ||
			i == '.' ||
			i == '_' ||
			i == '~' ||
			i == '/'
	}
}

// uriEncode implements "Amazon-style" URL escaping.
func uriEncode(path string) string {
	var buf bytes.Buffer
	for i := 0; i < len(path); i++ {
		c := path[i]
		if noEscape[c] {
			buf.WriteByte(c)
		} else {
			fmt.Fprintf(&buf, "%%%02X", c)
		}
	}
	return buf.String()
}

// encodeQuery returns the canonical query string for the given parameters, as
// required by the SigV4 canonical request.
//
// Each parameter name and value is URI-encoded individually, and the encoded
// name/value pairs are then sorted. The SigV4 specification is explicit that
// "the sorting occurs after encoding":
//
//	https://docs.aws.amazon.com/IAM/latest/UserGuide/create-signed-request.html
//
// The order of encoded and decoded forms can disagree, so the two are not
// interchangeable. The values "a/b" and "a-b" encode to "a%2Fb" and "a-b":
// '/' (0x2F) sorts after '-' (0x2D), but '%' (0x25) sorts before it, so the
// pair is ordered one way before encoding and the other way after. Sorting
// before encoding therefore produces a canonical request the service does not
// agree with, and the request is rejected as unauthorized.
//
// Unlike url.Values.Encode, a space is encoded as "%20" rather than "+", which
// is what SigV4 requires.
func encodeQuery(query url.Values) string {
	type queryParam struct {
		key   string
		value string
	}

	params := make([]queryParam, 0, len(query))
	for key, values := range query {
		encodedKey := encodeQueryComponent(key)
		for _, value := range values {
			params = append(params, queryParam{
				key:   encodedKey,
				value: encodeQueryComponent(value),
			})
		}
	}

	sort.Slice(params, func(i, j int) bool {
		if params[i].key != params[j].key {
			return params[i].key < params[j].key
		}
		return params[i].value < params[j].value
	})

	var canonical strings.Builder
	for i, param := range params {
		if i > 0 {
			canonical.WriteByte('&')
		}
		canonical.WriteString(param.key)
		canonical.WriteByte('=')
		canonical.WriteString(param.value)
	}

	return canonical.String()
}

// encodeQueryComponent URI-encodes a single query string name or value the way
// SigV4 requires: every character outside the RFC 3986 unreserved set
// (A-Z a-z 0-9 - _ . ~) is percent-encoded with uppercase hex, and a space
// becomes "%20".
//
// This differs from uriEncode, which leaves '/' unescaped because it encodes
// path components. In a query string '/' must become "%2F".
//
// url.QueryEscape already matches this, except that it encodes a space as "+".
// A literal '+' is escaped to "%2B", so replacing '+' only rewrites spaces.
func encodeQueryComponent(s string) string {
	return strings.Replace(url.QueryEscape(s), "+", "%20", -1)
}

// rtosha computes the sha256 hash of the input Reader and rewinds it before
// returning.
func rtosha(r io.ReadSeeker) ([]byte, error) {
	h := sha256.New()
	if _, err := io.Copy(h, r); err != nil {
		return nil, err
	}
	if _, err := r.Seek(0, io.SeekStart); err != nil {
		return nil, err
	}

	return h.Sum(nil), nil
}

// Stosha computes the sha256 hash of the given string.
func Stosha(s string) []byte {
	h := sha256.New()
	h.Write([]byte(s))
	return h.Sum(nil)
}
