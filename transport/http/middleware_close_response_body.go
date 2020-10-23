package http

import (
	"context"
	"fmt"

	"github.com/awslabs/smithy-go/middleware"
	"github.com/awslabs/smithy-go/middleware/id"
)

// AddErrorCloseResponseBodyMiddleware adds the middleware to automatically
// close the response body of an operation request if the request response
// failed.
func AddErrorCloseResponseBodyMiddleware(stack *middleware.Stack) error {
	return stack.Deserialize.Add(&errorCloseResponseBodyMiddleware{}, middleware.Before)
}

type errorCloseResponseBodyMiddleware struct{}

func (*errorCloseResponseBodyMiddleware) ID() string {
	return id.ErrorCloseResponseBody
}

func (m *errorCloseResponseBodyMiddleware) HandleDeserialize(
	ctx context.Context, input middleware.DeserializeInput, next middleware.DeserializeHandler,
) (
	output middleware.DeserializeOutput, metadata middleware.Metadata, err error,
) {
	out, metadata, err := next.HandleDeserialize(ctx, input)
	if err != nil {
		if resp, ok := out.RawResponse.(*Response); ok && resp != nil && resp.Body != nil {
			// Do not validate that the response closes successfully.
			resp.Body.Close()
		}
	}

	return out, metadata, err
}

// AddCloseResponseBodyMiddleware adds the middleware to automatically close
// the response body of an operation request, after the response had been
// deserialized.
func AddCloseResponseBodyMiddleware(stack *middleware.Stack) error {
	return stack.Deserialize.Add(&closeResponseBody{}, middleware.Before)
}

type closeResponseBody struct{}

func (*closeResponseBody) ID() string {
	return id.CloseResponseBody
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
		if err = resp.Body.Close(); err != nil {
			return out, metadata, fmt.Errorf("close response body failed, %w", err)
		}
	}

	return out, metadata, err
}
