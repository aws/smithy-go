package httpbinding

import (
	"net/http"
	"net/url"
	"reflect"
	"testing"
)

func TestEncoder(t *testing.T) {
	actual := http.Request{
		Header: http.Header{
			"custom-user-header": {"someValue"},
		},
		URL: &url.URL{
			Path:     "/some/{pathKeyOne}/{pathKeyTwo}",
			RawQuery: "someExistingKeys=foobar",
		},
	}

	expected := http.Request{
		Header: map[string][]string{
			"custom-user-header": {"someValue"},
			"X-Amzn-Header-Foo":  {"someValue"},
			"X-Amzn-Meta-Foo":    {"someValue"},
		},
		URL: &url.URL{
			Path:     "/some/someValue/path",
			RawPath:  "/some/someValue/path",
			RawQuery: "someExistingKeys=foobar&someKey=someValue&someKey=otherValue",
		},
	}

	encoder := NewEncoder(&actual)

	// Headers
	encoder.AddHeader("x-amzn-header-foo").String("someValue")
	encoder.Headers("x-amzn-meta-").AddHeader("foo").String("someValue")

	// Query
	encoder.SetQuery("someKey").String("someValue")
	encoder.AddQuery("someKey").String("otherValue")

	// URI
	if err := encoder.SetURI("pathKeyOne").String("someValue"); err != nil {
		t.Errorf("expected no err, but got %v", err)
	}

	// URI
	if err := encoder.SetURI("pathKeyTwo").String("path"); err != nil {
		t.Errorf("expected no err, but got %v", err)
	}

	if err := encoder.Encode(); err != nil {
		t.Errorf("expected no err, but got %v", err)
	}

	if !reflect.DeepEqual(expected, actual) {
		t.Errorf("expected %v, but got %v", expected, actual)
	}
}
