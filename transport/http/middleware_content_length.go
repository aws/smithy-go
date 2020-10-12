package http

import (
	"context"
	"fmt"

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

	// do nothing if request content-length was set to 0 or above.
	if req.ContentLength >= 0 {
		return next.HandleBuild(ctx, in)
	}

	// attempt to compute stream length
	if n, ok, err := req.StreamLength(); err != nil {
		return out, metadata, fmt.Errorf(
			"failed getting length of request stream, %w", err)
	} else if ok {
		req.ContentLength = n
	}

	return next.HandleBuild(ctx, in)
}

// validateContentLengthMiddleware provides a middleware to validate the content-length
// is valid (greater than zero), for the serialized request payload.
type validateContentLengthMiddleware struct{}

// ValidateContentLengthHeader adds middleware that validates request content-length
// is set to value greater than zero.
func ValidateContentLengthHeader(stack *middleware.Stack) {
	stack.Build.Add(&validateContentLengthMiddleware{}, middleware.After)
}

// ID the identifier for the ContentLengthMiddleware
func (m *validateContentLengthMiddleware) ID() string { return "ValidateContentLengthMiddleware" }

// HandleBuild adds the length of the serialized request to the HTTP header
// if the length can be determined.
func (m *validateContentLengthMiddleware) HandleBuild(
	ctx context.Context, in middleware.BuildInput, next middleware.BuildHandler,
) (
	out middleware.BuildOutput, metadata middleware.Metadata, err error,
) {
	req, ok := in.Request.(*Request)
	if !ok {
		return out, metadata, fmt.Errorf("unknown request type %T", req)
	}

	// if request content-length was set to less than 0, return an error
	if req.ContentLength < 0 {
		return out, metadata, fmt.Errorf(
			"content length for payload is required and must be at least 0")
	}

	return next.HandleBuild(ctx, in)
}
