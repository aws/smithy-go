package middleware_test

import (
	"context"
	"net/http"

	"github.com/awslabs/smithy-go/middleware"
)

// HTTPClientHandler is an example of an HTTP client handler that will round
// trip the request.
type HTTPClientHandler struct {
	Client interface {
		Do(*http.Request) (*http.Response, error)
	}
}

// Handle invokes the underlying client's behavior to round trip the request.
// Input is required to be *http.Request, and output will be *http.Response or
// error.
func (h *HTTPClientHandler) Handle(ctx context.Context, input interface{}) (interface{}, error) {
	req := input.(*http.Request)
	return h.Client.Do(req)
}

// Client is a mock SDK API client.
type Client struct {
	retryer           interface{}
	signMiddleware    middleware.FinalizeMiddleware
	httpClientHandler middleware.Handler
}

// GetObjectInput is a mock SDK operation input.
type GetObjectInput struct{}

// GetObjectResponse is a mock SDK operation response wrapper.
type GetObjectResponse struct {
	Result   *GetObjectOutput
	Metadata interface{} // TODO should be more specific type.
}

// GetObjectOutput is a mock SDK operation output.
type GetObjectOutput struct{}

// NewRetryMiddleware is a stub function that returns a mock middleware.
func NewRetryMiddleware(v interface{}) middleware.FinalizeMiddleware { return nil }

// modifyStack provides a way for customers to provide client wide modification
// of request middleware stack. User should be able to specify a callback to do
// this behavior.
func (c *Client) modifyStack(*middleware.Stack) {
}

//  GetObject mock operation
func (c *Client) GetObject(ctx context.Context, input *GetObjectInput, opts ...func(stack *middleware.Stack, httpClient *middleware.Handler)) (
	*GetObjectResponse, error,
) {
	stack := middleware.NewStack("example stack")
	stack.Finalize.Add(NewRetryMiddleware(c.retryer), middleware.After)
	stack.Finalize.Add(c.signMiddleware, middleware.After)
	stack.Deserialize.Add(deserializeGetObjectOperation{}, middleware.After)

	// TODO Add middleware to stack specific to operation

	httpClientHandler := c.httpClientHandler

	c.modifyStack(stack)
	for _, o := range opts {
		o(stack, &httpClientHandler)
	}

	handler := middleware.DecorateHandler(httpClientHandler, stack)

	res, err := handler.Handle(context.Background(), input)
	if err != nil {
		return nil, err
	}

	return res.(*GetObjectResponse), nil
}

type deserializeGetObjectOperation struct{}

func (deserializeGetObjectOperation) ID() string { return "S3 GetObject deserializer" }

// HandleDeserialize is a deserialization middleware that deserializes the
// underlying raw response into the GetObjectResponse and Output.
func (d deserializeGetObjectOperation) HandleDeserialize(ctx context.Context, in middleware.DeserializeInput, next middleware.DeserializeHandler) (
	out middleware.DeserializeOutput, err error,
) {
	res, err := next.HandleDeserialize(ctx, in)
	if err != nil {
		return middleware.DeserializeOutput{}, err
	}

	// TODO do deserialization

	return middleware.DeserializeOutput{
		RawResponse: res.RawResponse,
		Result: &GetObjectResponse{
			Result:   nil, // TODO populate
			Metadata: nil, // TODO get metadata from raw response
		},
	}, nil
}
