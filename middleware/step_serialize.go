package middleware

import "context"

// SerializeInput provides the input parameters for serializing input for a
// handler.
type SerializeInput struct {
	Parameters interface{}
	Request    interface{}
}

// SerializeOutput provides the result of serialize handler middleware stack.
type SerializeOutput struct {
	Result interface{}
}

// SerializeHandler provides the interface for handling the initialization
// step of a middleware stack. Wraps the underlying handler.
type SerializeHandler interface {
	HandleSerialize(ctx context.Context, in SerializeInput) (
		out SerializeOutput, err error,
	)
}

// SerializeMiddleware provides the interface for middleware specific to the
// serialize step.
type SerializeMiddleware interface {
	Name() string
	HandleSerialize(ctx context.Context, in SerializeInput, next SerializeHandler) (
		out SerializeOutput, err error,
	)
}

// SerializeMiddlewareFunc wraps a function to satisfy the
// SerializeMiddleware interface.
type SerializeMiddlewareFunc func(ctx context.Context, in SerializeInput, next SerializeHandler) (
	out SerializeOutput, err error,
)

var _ SerializeMiddleware = (SerializeMiddlewareFunc)(nil)

// Name returns an empty string that will be replaced for a stub value when
// added to the SerializeStep.
func (f SerializeMiddlewareFunc) Name() string { return "" }

// HandleSerialize invokes the function with passed in parameters. Returning
// the result or error.
//
// Implements SerializeMiddleware interface.
func (f SerializeMiddlewareFunc) HandleSerialize(ctx context.Context, in SerializeInput, next SerializeHandler) (
	out SerializeOutput, err error,
) {
	return f(ctx, in, next)
}

// SerializeStep provides the ordered grouping of SerializeMiddleware to be
// invoked on an handler.
type SerializeStep struct {
	group orderedGroup
}

var _ Middleware = (*SerializeStep)(nil)

// Name returns the name of the initialization step.
func (s *SerializeStep) Name() string {
	return "Serialize stack step"
}

// HandleMiddleware invokes the middleware by decorating the next handler
// provided. Returns the result of the middleware and handler being invoked.
//
// Implements Middleware interface.
func (s *SerializeStep) HandleMiddleware(ctx context.Context, in interface{}, next Handler) (
	out interface{}, err error,
) {
	order := s.group.GetOrder()

	var h SerializeHandler = serializeWrapHandler{Next: next}
	for i := len(order); i >= 0; i-- {
		h = decorateSerializeHandler{
			Next: h,
			With: order[i].(SerializeMiddleware),
		}
	}

	sIn := SerializeInput{
		Parameters: in,
	}

	res, err := h.HandleSerialize(ctx, sIn)
	if err != nil {
		return nil, err
	}

	return res.Result, nil
}

// Add injects the middleware to the relative position of the middleware group.
// Returns an error if the middleware already exists.
func (s *SerializeStep) Add(m SerializeMiddleware, pos RelativePosition) error {
	return s.group.Add(m, pos)
}

// Insert injects the middleware relative to an existing middleware name.
// Return error if the original middleware does not exist, or the middleware
// being added already exists.
func (s *SerializeStep) Insert(m SerializeMiddleware, relativeTo string, pos RelativePosition) error {
	return s.group.Insert(m, relativeTo, pos)
}

// Swap removes the middleware by name, replacing it with the new middleware.
// Returns error if the original middleware doesn't exist.
func (s *SerializeStep) Swap(name string, m SerializeMiddleware) error {
	return s.group.Swap(name, m)
}

// Remove removes the middleware by name. Returns error if the middleware
// doesn't exist.
func (s *SerializeStep) Remove(name string) error {
	return s.group.Remove(name)
}

type serializeWrapHandler struct {
	Next Handler
}

var _ SerializeHandler = (*serializeWrapHandler)(nil)

// Implements SerializeHandler, converts types and delegates to underlying
// generic handler.
func (w serializeWrapHandler) HandleSerialize(ctx context.Context, in SerializeInput) (
	out SerializeOutput, err error,
) {
	res, err := w.Next.Handle(ctx, in.Request)
	if err != nil {
		return SerializeOutput{}, err
	}

	return SerializeOutput{
		Result: res,
	}, nil
}

type decorateSerializeHandler struct {
	Next SerializeHandler
	With SerializeMiddleware
}

var _ SerializeHandler = (*decorateSerializeHandler)(nil)

func (h decorateSerializeHandler) HandleSerialize(ctx context.Context, in SerializeInput) (
	out SerializeOutput, err error,
) {
	return h.With.HandleSerialize(ctx, in, h.Next)
}
