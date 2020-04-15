package http

import (
	"context"
	"fmt"
	"io"
	"io/ioutil"
	"net/http"
)

// Request provides the HTTP specific request structure for HTTP specific
// middleware steps to use to serialize input, and send an operation's request.
type Request struct {
	*http.Request
	stream           io.Reader
	isStreamSeekable bool
	streamStartPos   int64
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

// Clone returns a deep copy of the Request for the new context. A reference to
// the Stream is copied, but the underlying stream is not copied.
func (r *Request) Clone() *Request {
	rc := *r
	rc.Request = rc.Request.Clone(context.TODO())
	return &rc
}

// RewindStream will rewind the io.Reader to the relative start position if it is an io.Seeker
func (r *Request) RewindStream() error {
	if !r.isStreamSeekable {
		return fmt.Errorf("request stream is not seekable")
	}
	_, err := r.stream.(io.Seeker).Seek(r.streamStartPos, io.SeekStart)
	return err
}

// GetStream returns the request stream io.Reader
func (r *Request) GetStream() io.Reader {
	return r.stream
}

// SetStream returns a clone of the request with the stream set to the provided reader.
// May return an error if the provided reader is seekable but returns an error.
func (r *Request) SetStream(reader io.Reader) (rc *Request, err error) {
	rc = r.Clone()

	switch v := reader.(type) {
	case io.Seeker:
		rc.isStreamSeekable = true
		n, err := v.Seek(0, io.SeekCurrent)
		if err != nil {
			return rc, err
		}
		rc.streamStartPos = n
	default:
		rc.isStreamSeekable = false
	}
	rc.stream = reader

	return rc, err
}

// Build returns a build standard HTTP request value from the Smithy request.
// The request's stream is wrapped in a safe container that allows it to be
// reused for subsiquent attempts.
func (r *Request) Build(ctx context.Context) *http.Request {
	req := r.Request.Clone(ctx)

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

	req.Body = ioutil.NopCloser(r.stream)

	return req
}
