package http

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"io/ioutil"

	"github.com/awslabs/smithy-go/middleware"
)

const contentMD5Header = "Content-Md5"

// ChecksumMiddleware provides a middleware to compute and set
// required checksum for a http request
type checksumMiddleware struct {
}

// AddChecksumMiddleware adds checksum middleware to middleware's
// build step.
func AddChecksumMiddleware(stack *middleware.Stack) {
	stack.Build.Add(&checksumMiddleware{}, middleware.After)
}

// ID the identifier for the checksum middleware
func (m *checksumMiddleware) ID() string { return "ChecksumRequiredMiddleware" }

// HandleBuild adds behavior to compute md5 checksum and add content-md5 header
// on http request
func (m *checksumMiddleware) HandleBuild(
	ctx context.Context, in middleware.BuildInput, next middleware.BuildHandler,
) (
	out middleware.BuildOutput, metadata middleware.Metadata, err error,
) {
	req, ok := in.Request.(*Request)
	if !ok {
		return out, metadata, fmt.Errorf("unknown request type %T", req)
	}

	// if Content-MD5 header is already present, return
	if v := req.Header.Get(contentMD5Header); len(v) != 0 {
		return next.HandleBuild(ctx, in)
	}

	readBuff := make([]byte, 0)
	readBody := bytes.NewBuffer(readBuff)
	body := io.TeeReader(req.Body, readBody)

	// compute md5 checksum
	v, err := computeMD5Checksum(body)
	if err != nil {
		return out, metadata, fmt.Errorf("error computing md5 checksum, %w", err)
	}

	// reset the request body
	req.Body = ioutil.NopCloser(readBody)

	// set md5 header value
	req.Header.Set("Content-MD5", string(v))
	return next.HandleBuild(ctx, in)
}
