package middleware

import "context"

// Handler provides the interface for performing the logic to obtain an output,
// or error for the given input.
type Handler interface {
	// Handle performs logic to obtain an output for the given input. Handler
	// should be decorated with middleware to perform input specific behavior.
	Handle(ctx context.Context, input interface{}) (output interface{}, err error)
}

// Middleware provides the interface to call handlers in a chain.
type Middleware interface {
	// Performs the middleware's handling of the input, returning the output,
	// or error. The middleware can invoke the next Handler if handling should
	// continue.
	HandleMiddleware(ctx context.Context, input interface{}, next Handler) (
		output interface{}, err error,
	)
}

// MiddlewareHandler wraps a middleware in order to to call the next handler in
// the chain.
type MiddlewareHandler struct {
	// The next handler to be called.
	Next Handler

	// The current middleware decorating the handler.
	With Middleware
}

// Handle implements the Handler interface to handle a operation invocation.
func (m MiddlewareHandler) Handle(ctx context.Context, input interface{}) (
	output interface{}, err error,
) {
	return m.With.HandleMiddleware(ctx, input, m.Next)
}

// DecorateHandler decorates a handler with a middleware. Wrapping the handler
// with the middleware.
func DecorateHandler(h Handler, with ...Middleware) Handler {
	for i := len(with); i >= 0; i-- {
		h = MiddlewareHandler{
			Next: h,
			With: with[i],
		}
	}

	return h
}

// Middlewares provides a collection of middleware that can be invoked as a
// stack on a handler.
type Middlewares []Middleware

// HandleMiddleware invokes the middleware, decorating the handler.
func (ms Middlewares) HandleMiddleware(ctx context.Context, input interface{}, next Handler) (
	output interface{}, err error,
) {
	next = DecorateHandler(next, ms...)
	return next.Handle(ctx, input)
}
