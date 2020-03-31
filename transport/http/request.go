package http

import (
	"context"
	"io"
	"net/http"
)

// Request provides the HTTP specific request structure for HTTP specific
// middleware steps to use to serialize input, and send an operation's request.
type Request struct {
	*http.Request
	Stream io.Reader
}

// NewRequest returns an initialize request ready to populated with the HTTP
// request details.
func NewRequest() *Request {
	return &Request{
		Request: &http.Request{
			Header: http.Header{},
		},
	}
}

// WithContext returns a shallow copy of the Request for the new context.
func (r *Request) WithContext(ctx context.Context) *Request {
	return &Request{
		Request: r.Request.WithContext(ctx),
		Stream:  r.Stream,
	}
}

// Clone returns a deep copy of the Request for the new context. A reference to
// the Stream is copied, but the underlying stream is not copied.
func (r *Request) Clone(ctx context.Context) *Request {
	return &Request{
		Request: r.Request.Clone(ctx),
		Stream:  r.Stream,
	}
}
