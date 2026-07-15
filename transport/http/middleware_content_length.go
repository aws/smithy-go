package http

import (
	"context"
	"fmt"

	"github.com/aws/smithy-go/middleware"
)

// ComputeRequestContentLength sets req.ContentLength to the length of the
// serialized request body when it can be determined and is not already set.
// It is a no-op if the content length is already known (>= 0).
//
// Generated operation serializers call this after the request body is
// serialized, so the value is available to downstream consumers (for example,
// the SigV4 signer, which signs the content-length header). It replaces the
// need for a standalone ComputeContentLength middleware on the stack.
func ComputeRequestContentLength(req *Request) error {
	// do nothing if request content-length was set to 0 or above.
	if req.ContentLength >= 0 {
		return nil
	}

	// attempt to compute stream length
	if n, ok, err := req.StreamLength(); err != nil {
		return fmt.Errorf("failed getting length of request stream, %w", err)
	} else if ok {
		req.ContentLength = n
	}

	return nil
}

// ComputeContentLength provides a middleware to set the content-length
// header for the length of a serialize request body.
//
// Deprecated: generated operation serializers now compute the request
// content length via ComputeRequestContentLength, so this middleware is no
// longer used.
type ComputeContentLength struct {
}

// AddComputeContentLengthMiddleware adds ComputeContentLength to the middleware
// stack's Build step.
//
// Deprecated: generated operation serializers now compute the request
// content length via ComputeRequestContentLength, so this middleware is no
// longer used.
func AddComputeContentLengthMiddleware(stack *middleware.Stack) error {
	return stack.Build.Add(&ComputeContentLength{}, middleware.After)
}

// ID returns the identifier for the ComputeContentLength.
func (m *ComputeContentLength) ID() string { return "ComputeContentLength" }

// HandleBuild adds the length of the serialized request to the HTTP header
// if the length can be determined.
func (m *ComputeContentLength) HandleBuild(
	ctx context.Context, in middleware.BuildInput, next middleware.BuildHandler,
) (
	out middleware.BuildOutput, metadata middleware.Metadata, err error,
) {
	req, ok := in.Request.(*Request)
	if !ok {
		return out, metadata, fmt.Errorf("unknown request type %T", req)
	}

	if err := ComputeRequestContentLength(req); err != nil {
		return out, metadata, err
	}

	return next.HandleBuild(ctx, in)
}

// validateContentLength provides a middleware to validate the content-length
// is valid (greater than zero), for the serialized request payload.
type validateContentLength struct{}

// ValidateContentLengthHeader adds middleware that validates request content-length
// is set to value greater than zero.
func ValidateContentLengthHeader(stack *middleware.Stack) error {
	return stack.Build.Add(&validateContentLength{}, middleware.After)
}

// ID returns the identifier for the ComputeContentLength.
func (m *validateContentLength) ID() string { return "ValidateContentLength" }

// HandleBuild adds the length of the serialized request to the HTTP header
// if the length can be determined.
func (m *validateContentLength) HandleBuild(
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
