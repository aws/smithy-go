package httpbinding

import (
	"fmt"
	"net/http"
	"net/url"
	"strings"
)

// An Encoder provides encoding of REST URI path, query, and header components
// of an HTTP request. Can also encode a stream as the payload.
//
// Does not support SetFields.
type Encoder struct {
	path, rawPath, pathBuffer []byte

	query  url.Values
	header http.Header
}

// NewEncoder creates a new encoder from the passed in request. All query and
// header values will be added on top of the request's existing values. Overwriting
// duplicate values.
func NewEncoder(path, query string, headers http.Header) (*Encoder, error) {
	parseQuery, err := url.ParseQuery(query)
	if err != nil {
		return nil, fmt.Errorf("failed to parse query string: %w", err)
	}

	e := &Encoder{
		path:    []byte(path),
		rawPath: []byte(path),
		query:   parseQuery,
		header:  headers.Clone(),
	}

	return e, nil
}

// Encode returns a REST protocol encoder for encoding HTTP bindings
// Returns any error if one occurred during encoding.
func (e *Encoder) Encode(req *http.Request) (*http.Request, error) {
	req.URL.Path, req.URL.RawPath = string(e.path), string(e.rawPath)
	req.URL.RawQuery = e.query.Encode()
	req.Header = e.header

	return req, nil
}

// AddHeader returns a HeaderValue for appending to the given header name
func (e *Encoder) AddHeader(key string) HeaderValue {
	return newHeaderValue(e.header, key, true)
}

// SetHeader returns a HeaderValue for setting the given header name
func (e *Encoder) SetHeader(key string) HeaderValue {
	return newHeaderValue(e.header, key, false)
}

// Headers returns a Header used encoding headers with the given prefix
func (e *Encoder) Headers(prefix string) Headers {
	return Headers{
		header: e.header,
		prefix: strings.TrimSpace(prefix),
	}
}

// SetURI returns a URIValue used for setting the given path key
func (e *Encoder) SetURI(key string) URIValue {
	return newURIValue(&e.path, &e.rawPath, &e.pathBuffer, key)
}

// SetQuery returns a QueryValue used for setting the given query key
func (e *Encoder) SetQuery(key string) QueryValue {
	return newQueryValue(e.query, key, false)
}

// AddQuery returns a QueryValue used for appending the given query key
func (e *Encoder) AddQuery(key string) QueryValue {
	return newQueryValue(e.query, key, true)
}
