package http

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strconv"
	"strings"
	"testing"
)

func TestRequestRewindable(t *testing.T) {
	cases := map[string]struct {
		Stream    io.Reader
		ExpectErr string
	}{
		"rewindable": {
			Stream: bytes.NewReader([]byte{}),
		},
		"empty not rewindable": {
			Stream: bytes.NewBuffer([]byte{}),
			// ExpectErr: "stream is not seekable",
		},
		"not empty not rewindable": {
			Stream:    bytes.NewBuffer([]byte("abc123")),
			ExpectErr: "stream is not seekable",
		},
		"nil stream": {},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			req := NewStackRequest().(*Request)

			req, err := req.SetStream(c.Stream)
			if err != nil {
				t.Fatalf("expect no error setting stream, %v", err)
			}

			err = req.RewindStream()
			if len(c.ExpectErr) != 0 {
				if err == nil {
					t.Fatalf("expect error, got none")
				}
				if e, a := c.ExpectErr, err.Error(); !strings.Contains(a, e) {
					t.Fatalf("expect error to contain %v, got %v", e, a)
				}
				return
			}
			if err != nil {
				t.Fatalf("expect no error, got %v", err)
			}
		})
	}
}

// pipeReader is a stream whose length cannot be determined from the reader
// itself, so only a caller-supplied ContentLength can frame the request.
func pipeReader(t *testing.T) *io.PipeReader {
	t.Helper()
	r, w := io.Pipe()
	t.Cleanup(func() {
		r.Close()
		w.Close()
	})
	return r
}

func TestRequestBuild_contentLength(t *testing.T) {
	cases := []struct {
		Request  *Request
		Expected int64
	}{
		{
			Request: &Request{
				Request: &http.Request{
					ContentLength: 100,
				},
			},
			Expected: 100,
		},
		{
			Request: &Request{
				Request: &http.Request{
					ContentLength: -1,
				},
			},
			Expected: 0,
		},
		{
			Request: &Request{
				Request: &http.Request{
					ContentLength: 100,
				},
				stream: bytes.NewReader(make([]byte, 100)),
			},
			Expected: 100,
		},
		{
			Request: &Request{
				Request: &http.Request{
					ContentLength: 100,
				},
				stream: http.NoBody,
			},
			Expected: 100,
		},
		{
			Request: &Request{
				Request: &http.Request{
					ContentLength: 100,
				},
				stream: pipeReader(t),
			},
			Expected: 100,
		},
		{
			Request: &Request{
				Request: &http.Request{
					ContentLength: 0,
				},
				stream: pipeReader(t),
			},
			Expected: -1,
		},
		{
			Request: &Request{
				Request: &http.Request{
					ContentLength: -1,
				},
				stream: pipeReader(t),
			},
			Expected: -1,
		},
	}

	for i, tt := range cases {
		t.Run(strconv.Itoa(i), func(t *testing.T) {
			build := tt.Request.Build(context.Background())

			if build.ContentLength != tt.Expected {
				t.Errorf("expect %v, got %v", tt.Expected, build.ContentLength)
			}
		})
	}
}

// TestRequestBuild_pipeReaderFraming covers what the ContentLength on a built
// request means once it reaches a server. A pipe cannot report its own length,
// so an unknown one must still fall back to chunked encoding, but a length the
// caller supplied has to survive as a Content-Length header: signing runs
// before Build and has already signed that header in.
func TestRequestBuild_pipeReaderFraming(t *testing.T) {
	const body = "hello world"

	cases := map[string]struct {
		ContentLength  int64
		ExpectedHeader string
		ExpectChunked  bool
	}{
		"caller supplied length": {
			ContentLength:  int64(len(body)),
			ExpectedHeader: strconv.Itoa(len(body)),
		},
		"unknown length": {
			ContentLength: -1,
			ExpectChunked: true,
		},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			type framing struct {
				header           string
				transferEncoding []string
			}
			received := make(chan framing, 1)

			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				io.Copy(io.Discard, r.Body)
				received <- framing{
					header:           r.Header.Get("Content-Length"),
					transferEncoding: r.TransferEncoding,
				}
			}))
			defer server.Close()

			pr, pw := io.Pipe()
			go func() {
				io.WriteString(pw, body)
				pw.Close()
			}()

			req := NewStackRequest().(*Request)
			req, err := req.SetStream(pr)
			if err != nil {
				t.Fatalf("expect no error setting stream, got %v", err)
			}
			req.Method = http.MethodPut
			if req.URL, err = url.Parse(server.URL); err != nil {
				t.Fatalf("expect no error parsing server URL, got %v", err)
			}
			req.ContentLength = c.ContentLength

			resp, err := server.Client().Do(req.Build(context.Background()))
			if err != nil {
				t.Fatalf("expect no error sending request, got %v", err)
			}
			resp.Body.Close()

			got := <-received
			if e, a := c.ExpectedHeader, got.header; e != a {
				t.Errorf("expect Content-Length %q, got %q", e, a)
			}
			chunked := len(got.transferEncoding) == 1 && got.transferEncoding[0] == "chunked"
			if e, a := c.ExpectChunked, chunked; e != a {
				t.Errorf("expect chunked %v, got %v from %v", e, a, got.transferEncoding)
			}
		})
	}
}

func TestRequestSetStream(t *testing.T) {
	cases := map[string]struct {
		reader                 io.Reader
		expectSeekable         bool
		expectStreamStartPos   int64
		expectContentLength    int64
		expectNilStream        bool
		expectNilBody          bool
		expectReqContentLength int64
		expectErr              string
	}{
		"nil stream": {
			expectNilStream: true,
			expectNilBody:   true,
		},
		"empty unseekable stream": {
			reader:          bytes.NewBuffer([]byte{}),
			expectNilStream: true,
			expectNilBody:   true,
		},
		"empty seekable stream": {
			reader:              bytes.NewReader([]byte{}),
			expectContentLength: 0,
			expectSeekable:      true,
			expectNilStream:     false,
			expectNilBody:       true,
		},
		"unseekable no len stream": {
			reader:                 io.NopCloser(bytes.NewBuffer([]byte("abc123"))),
			expectContentLength:    -1,
			expectNilStream:        false,
			expectNilBody:          false,
			expectReqContentLength: -1,
		},
		"unseekable stream": {
			reader:                 bytes.NewBuffer([]byte("abc123")),
			expectContentLength:    6,
			expectNilStream:        false,
			expectNilBody:          false,
			expectReqContentLength: 6,
		},
		"seekable stream": {
			reader:                 bytes.NewReader([]byte("abc123")),
			expectContentLength:    6,
			expectNilStream:        false,
			expectSeekable:         true,
			expectNilBody:          false,
			expectReqContentLength: 6,
		},
		"offset seekable stream": {
			reader: func() io.Reader {
				r := bytes.NewReader([]byte("abc123"))
				_, _ = r.Seek(1, os.SEEK_SET)
				return r
			}(),
			expectStreamStartPos:   1,
			expectContentLength:    5,
			expectSeekable:         true,
			expectNilStream:        false,
			expectNilBody:          false,
			expectReqContentLength: 5,
		},
		"NoBody stream": {
			reader:          http.NoBody,
			expectNilStream: true,
			expectNilBody:   true,
		},
		// Seeking to compute the length fails.
		"seek error": {
			reader:    &errorSecondSeekableReader{err: fmt.Errorf("seek failed")},
			expectErr: "seek failed",
		},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			var err error
			req := NewStackRequest().(*Request)
			req, err = req.SetStream(c.reader)
			if len(c.expectErr) != 0 {
				if err == nil {
					t.Fatalf("expect error, got none")
				}
				if e, a := c.expectErr, err.Error(); !strings.Contains(a, e) {
					t.Fatalf("expect error to contain %q, got %v", e, a)
				}
				return
			}
			if err != nil {
				t.Fatalf("expect not error, got %v", err)
			}

			if e, a := c.expectSeekable, req.IsStreamSeekable(); e != a {
				t.Errorf("expect %v seekable, got %v", e, a)
			}
			if e, a := c.expectStreamStartPos, req.streamStartPos; e != a {
				t.Errorf("expect %v seek start position, got %v", e, a)
			}
			if e, a := c.expectNilStream, req.stream == nil; e != a {
				t.Errorf("expect %v nil stream, got %v", e, a)
			}

			if e, a := c.expectContentLength, req.ContentLength; e != a {
				t.Errorf("expect %v content-length, got %v", e, a)
			}
			if e, a := c.expectStreamStartPos, req.streamStartPos; e != a {
				t.Errorf("expect %v streamStartPos, got %v", e, a)
			}

			r := req.Build(context.Background())
			if e, a := c.expectNilBody, r.Body == nil; e != a {
				t.Errorf("expect %v request nil body, got %v", e, a)
			}
			if e, a := c.expectContentLength, req.ContentLength; e != a {
				t.Errorf("expect %v request content-length, got %v", e, a)
			}
		})
	}
}
