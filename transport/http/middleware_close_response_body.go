package http

import (
	"context"
	"io"

	"github.com/aws/smithy-go/logging"
	"github.com/aws/smithy-go/middleware"
)

// DrainAndCloseResponseBody drains and closes the HTTP response body. It always
// closes on error; on success it closes only when closeOnSuccess is true (pass
// false when the response is a streaming payload owned by the caller).
func DrainAndCloseResponseBody(ctx context.Context, resp *Response, closeOnSuccess bool, opErr error) {
	if resp == nil || resp.Body == nil {
		return
	}

	if opErr != nil {
		// Consume the full body to prevent TCP connection resets on some platforms.
		_, _ = io.Copy(io.Discard, resp.Body)
		// Do not validate that the response closes successfully on the error path.
		resp.Body.Close()
		return
	}

	if !closeOnSuccess {
		return
	}

	if _, copyErr := io.Copy(io.Discard, resp.Body); copyErr != nil {
		middleware.GetLogger(ctx).Logf(logging.Warn, "failed to discard remaining HTTP response body, this may affect connection reuse")
	}
	if closeErr := resp.Body.Close(); closeErr != nil {
		middleware.GetLogger(ctx).Logf(logging.Warn, "failed to close HTTP response body, this may affect connection reuse")
	}
}

// AddErrorCloseResponseBodyMiddleware adds the middleware to automatically
// close the response body of an operation request if the request response
// failed.
//
// Deprecated: generated operation deserializers now close the response body
// via DrainAndCloseResponseBody, so this middleware is no longer used.
func AddErrorCloseResponseBodyMiddleware(stack *middleware.Stack) error {
	return stack.Deserialize.Insert(&errorCloseResponseBodyMiddleware{}, "OperationDeserializer", middleware.Before)
}

type errorCloseResponseBodyMiddleware struct{}

func (*errorCloseResponseBodyMiddleware) ID() string {
	return "ErrorCloseResponseBody"
}

func (m *errorCloseResponseBodyMiddleware) HandleDeserialize(
	ctx context.Context, input middleware.DeserializeInput, next middleware.DeserializeHandler,
) (
	output middleware.DeserializeOutput, metadata middleware.Metadata, err error,
) {
	out, metadata, err := next.HandleDeserialize(ctx, input)
	if err != nil {
		if resp, ok := out.RawResponse.(*Response); ok {
			DrainAndCloseResponseBody(ctx, resp, false, err)
		}
	}
	return out, metadata, err
}

// AddCloseResponseBodyMiddleware adds the middleware to automatically close
// the response body of an operation request, after the response had been
// deserialized.
//
// Deprecated: generated operation deserializers now close the response body
// via DrainAndCloseResponseBody, so this middleware is no longer used.
func AddCloseResponseBodyMiddleware(stack *middleware.Stack) error {
	return stack.Deserialize.Insert(&closeResponseBody{}, "OperationDeserializer", middleware.Before)
}

type closeResponseBody struct{}

func (*closeResponseBody) ID() string {
	return "CloseResponseBody"
}

func (m *closeResponseBody) HandleDeserialize(
	ctx context.Context, input middleware.DeserializeInput, next middleware.DeserializeHandler,
) (
	output middleware.DeserializeOutput, metadata middleware.Metadata, err error,
) {
	out, metadata, err := next.HandleDeserialize(ctx, input)
	if err != nil {
		return out, metadata, err
	}
	if resp, ok := out.RawResponse.(*Response); ok {
		DrainAndCloseResponseBody(ctx, resp, true, nil)
	}
	return out, metadata, err
}
