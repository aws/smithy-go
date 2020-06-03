package http

import (
	"context"
	"fmt"
	"net/http"

	"github.com/awslabs/smithy-go/middleware"
)

// ClientDo provides the interface for custom HTTP client implementations.
type ClientDo interface {
	Do(*http.Request) (*http.Response, error)
}

// ClientDoFunc provides a helper to wrap an function as an HTTP client for
// round tripping requests.
type ClientDoFunc func(*http.Request) (*http.Response, error)

// Do will invoke the underlying func, returning the result.
func (fn ClientDoFunc) Do(r *http.Request) (*http.Response, error) {
	return fn(r)
}

// ClientHandler wraps a client that implements the HTTP Do method. Standard
// implementation is http.Client.
type ClientHandler struct {
	client ClientDo
}

// NewClientHandler returns an initialized middleware handler for the client.
func NewClientHandler(client ClientDo) ClientHandler {
	return ClientHandler{
		client: client,
	}
}

// Handle implements the middleware Handler interface, that will invoke the
// underlying HTTP client. Requires the input to be an Smithy *Request. Returns
// a smithy *Response, or error if the request failed.
func (c ClientHandler) Handle(ctx context.Context, input interface{}) (
	out interface{}, metadata middleware.Metadata, err error,
) {
	req, ok := input.(*Request)
	if !ok {
		return nil, metadata, fmt.Errorf("expect Smithy http.Request value as input, got unsupported type %T", input)
	}

	resp, err := c.client.Do(req.Build(ctx))
	if err != nil {
		return nil, metadata, err
	}

	return &Response{Response: resp}, metadata, nil
}
