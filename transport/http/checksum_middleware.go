package http

import (
	"context"
	"fmt"

	"github.com/awslabs/smithy-go/middleware"
)

const contentMD5Header = "Content-Md5"

// contentMD5ChecksumMiddleware provides a middleware to compute and set
// content-md5 checksum for a http request
type contentMD5ChecksumMiddleware struct {
}

// AddChecksumMiddleware adds checksum middleware to middleware's
// build step.
func AddChecksumMiddleware(stack *middleware.Stack) {
	// This middleware must be executed before request body is set.
	stack.Build.Add(&contentMD5ChecksumMiddleware{}, middleware.Before)
}

// ID the identifier for the checksum middleware
func (m *contentMD5ChecksumMiddleware) ID() string { return "ChecksumRequiredMiddleware" }

// HandleBuild adds behavior to compute md5 checksum and add content-md5 header
// on http request
func (m *contentMD5ChecksumMiddleware) HandleBuild(
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

	// compute checksum if payload is explicit
	if req.stream != nil {
		v, err := computeMD5Checksum(req.stream)
		if err != nil {
			return out, metadata, fmt.Errorf("error computing md5 checksum, %w", err)
		}

		// reset the request stream
		req.RewindStream()

		// set the 'Content-MD5' header
		req.Header.Set(contentMD5Header, string(v))
	}

	// set md5 header value
	return next.HandleBuild(ctx, in)
}