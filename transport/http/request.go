package http

import (
	"context"
	"io"
	"io/ioutil"
	"net/http"
)

// Request provides the HTTP specific request structure for HTTP specific
// middleware steps to use to serialize input, and send an operation's request.
type Request struct {
	*http.Request
	Stream io.Reader
}

// NewStackRequest returns an initialized request ready to populated with the
// HTTP request details. Returns empty interface so the function can be used as
// a parameter to the Smithy middleware Stack constructor.
func NewStackRequest() interface{} {
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

	// TODO When should the underlying stream be rewound for next attempt?

	return &Request{
		Request: r.Request.Clone(ctx),
		Stream:  r.Stream,
	}
}

// Build returns a build standard HTTP request value from the Smithy request.
// The request's stream is wrapped in a safe container that allows it to be
// reused for subsiquent attempts.
func (r *Request) Build() *http.Request {
	req := r.Request.Clone(r.Request.Context())

	// TODO wrap Stream in a container that provides safe reuse and rewinding
	// of the underlying io.Reader. The standard http transport has race
	// condition where it holds onto Body after request has returned.
	//   * e.g. aws-sdk-go wraps with a safe reader
	//   * e.g. if Stream is a io.ReaderAt, optimize with wrapper that doesn't need locks.

	// TODO special handling for unbounded streams like HTTP/2 for eventstream,
	// or chunk transfer encoding.

	// TODO handle case of unseekable stream, e.g. io.Reader, not io.ReadSeeker, or io.ReaderAt.

	// TODO handle determination of when to close the underlying stream. Most
	// likely want to defer this to the caller, and not allow the underlying
	// http round tripper close the stream.

	req.Body = ioutil.NopCloser(r.Stream)

	return req
}
