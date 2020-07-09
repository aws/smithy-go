package http

import (
	"context"
	"fmt"
	"strconv"

	"github.com/awslabs/smithy-go/middleware"
)

// ContentLengthMiddleware provides a middleware to set the content-length
// header for the length of a serialize request body.
type ContentLengthMiddleware struct {
}

// AddContentLengthMiddleware adds ContentLengthMiddleware to the middleware
// stack's Build step.
func AddContentLengthMiddleware(stack *middleware.Stack) {
	stack.Build.Add(&ContentLengthMiddleware{}, middleware.After)
}

// ID the identifier for the ContentLengthMiddleware
func (m *ContentLengthMiddleware) ID() string { return "ContentLengthMiddleware" }

// HandleBuild adds the length of the serialized request to the HTTP header
// if the length can be determined.
func (m *ContentLengthMiddleware) HandleBuild(
	ctx context.Context, in middleware.BuildInput, next middleware.BuildHandler,
) (
	out middleware.BuildOutput, metadata middleware.Metadata, err error,
) {
	req, ok := in.Request.(*Request)
	if !ok {
		return out, metadata, fmt.Errorf("unknown request type %T", req)
	}

	// Don't set content length if header is already set.
	if vs := req.Header.Values("Content-Length"); len(vs) != 0 {
		return next.HandleBuild(ctx, in)
	}

	if n, ok, err := req.StreamLength(); err != nil {
		return out, metadata, fmt.Errorf(
			"failed getting length of request stream, %w", err)
	} else if ok {
		req.Header.Set("Content-Length", strconv.FormatInt(n, 10))
	}

	return next.HandleBuild(ctx, in)
}
