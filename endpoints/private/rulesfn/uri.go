package rulesfn

import (
	"fmt"
	"net/netip"
	"net/url"
	"strings"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// IsValidHostLabel returns if the input is a single valid [RFC 1123] host
// label. If allowSubDomains is true, will allow validation to include nested
// host labels. Returns false if the input is not a valid host label. If errors
// occur they will be added to the provided [ErrorCollector].
//
// [RFC 1123]: https://www.ietf.org/rfc/rfc1123.txt
func IsValidHostLabel(input string, allowSubDomains bool) bool {
	var labels []string
	if allowSubDomains {
		labels = strings.Split(input, ".")
	} else {
		labels = []string{input}
	}

	for _, label := range labels {
		if !smithyhttp.ValidHostLabel(label) {
			return false
		}
	}

	return true
}

// ParseURL returns a [URL] if the provided string could be parsed. Returns nil
// if the string could not be parsed. Any parsing error will be added to the
// [ErrorCollector].
//
// If the input URL string contains an IP6 address with a zone index. The
// returned [builtin.URL.Authority] value will contain the percent escaped (%)
// zone index separator.
func ParseURL(input string) *URL {
	u, err := url.Parse(input)
	if err != nil {
		return nil
	}

	if u.RawQuery != "" {
		return nil
	}

	if u.Scheme != "http" && u.Scheme != "https" {
		return nil
	}

	normalizedPath := u.Path
	if !strings.HasPrefix(normalizedPath, "/") {
		normalizedPath = "/" + normalizedPath
	}
	if !strings.HasSuffix(normalizedPath, "/") {
		normalizedPath = normalizedPath + "/"
	}

	// IP6 hosts may have zone indexes that need to be escaped to be valid in a
	// URI. The Go URL parser will unescape the `%25` into `%`. This needs to
	// be reverted since the returned URL will be used in string builders.
	authority := strings.ReplaceAll(u.Host, "%", "%25")

	var isIP bool
	if _, err = netip.ParseAddr(u.Hostname()); err == nil {
		isIP = true
	}

	return &URL{
		Scheme:         u.Scheme,
		Authority:      authority,
		Path:           u.Path,
		NormalizedPath: normalizedPath,
		IsIP:           isIP,
	}
}

// URL provides the structure describing the parts of a parsed URL returned by
// [ParseURL].
type URL struct {
	Scheme         string // https://www.rfc-editor.org/rfc/rfc3986#section-3.1
	Authority      string // https://www.rfc-editor.org/rfc/rfc3986#section-3.2
	Path           string // https://www.rfc-editor.org/rfc/rfc3986#section-3.3
	NormalizedPath string // https://www.rfc-editor.org/rfc/rfc3986#section-6.2.3
	IsIP           bool
}

// URIEncode returns an percent-encoded [RFC3986 section 2.1] version of the
// input string.
//
// [RFC3986 section 2.1]: https://www.rfc-editor.org/rfc/rfc3986#section-2.1
func URIEncode(input string) string {
	var output strings.Builder
	for _, c := range []byte(input) {
		if validPercentEncodedChar(c) {
			output.WriteByte(c)
			continue
		}

		fmt.Fprintf(&output, "%%%X", c)
	}

	return output.String()
}

func validPercentEncodedChar(c byte) bool {
	return (c >= 'a' && c <= 'z') ||
		(c >= 'A' && c <= 'Z') ||
		(c >= '0' && c <= '9') ||
		c == '-' || c == '_' || c == '.' || c == '~'
}
