package middleware

import "context"

// DeserializeInput provides the input parameters for serializing input for a
// handler.
type DeserializeInput struct {
	Request interface{}
}

// DeserializeOutput provides the result of deserialize handler middleware stack.
type DeserializeOutput struct {
	RawResponse interface{}
	Result      interface{}
}

// DeserializeHandler provides the interface for handling the initialization
// step of a middleware stack. Wraps the underlying handler.
type DeserializeHandler interface {
	HandleDeserialize(ctx context.Context, in DeserializeInput) (
		out DeserializeOutput, err error,
	)
}

// DeserializeMiddleware provides the interface for middleware specific to the
// deserialize step.
type DeserializeMiddleware interface {
	Name() string
	HandleDeserialize(ctx context.Context, in DeserializeInput, next DeserializeHandler) (
		out DeserializeOutput, err error,
	)
}

// DeserializeMiddlewareFunc wraps a function to satisfy the
// DeserializeMiddleware interface.
type DeserializeMiddlewareFunc func(ctx context.Context, in DeserializeInput, next DeserializeHandler) (
	out DeserializeOutput, err error,
)

var _ DeserializeMiddleware = (DeserializeMiddlewareFunc)(nil)

// Name returns an empty string that will be replaced for a stub value when
// added to the DeserializeStep.
func (f DeserializeMiddlewareFunc) Name() string { return "" }

// HandleDeserialize invokes the function with passed in parameters. Returning
// the result or error.
//
// Implements DeserializeMiddleware interface.
func (f DeserializeMiddlewareFunc) HandleDeserialize(ctx context.Context, in DeserializeInput, next DeserializeHandler) (
	out DeserializeOutput, err error,
) {
	return f(ctx, in, next)
}

// DeserializeStep provides the ordered grouping of DeserializeMiddleware to be
// invoked on an handler.
type DeserializeStep struct {
	group orderedGroup
}

var _ Middleware = (*DeserializeStep)(nil)

// Name returns the name of the initialization step.
func (s *DeserializeStep) Name() string {
	return "Deserialize stack step"
}

// HandleMiddleware invokes the middleware by decorating the next handler
// provided. Returns the result of the middleware and handler being invoked.
//
// Implements Middleware interface.
func (s *DeserializeStep) HandleMiddleware(ctx context.Context, in interface{}, next Handler) (
	out interface{}, err error,
) {
	order := s.group.GetOrder()

	var h DeserializeHandler = deserializeWrapHandler{Next: next}
	for i := len(order); i >= 0; i-- {
		h = decorateDeserializeHandler{
			Next: h,
			With: order[i].(DeserializeMiddleware),
		}
	}

	sIn := DeserializeInput{
		Request: in,
	}

	res, err := h.HandleDeserialize(ctx, sIn)
	if err != nil {
		return nil, err
	}

	return res.Result, nil
}

// Add injects the middleware to the relative position of the middleware group.
// Returns an error if the middleware already exists.
func (s *DeserializeStep) Add(m DeserializeMiddleware, pos RelativePosition) error {
	return s.group.Add(m, pos)
}

// Insert injects the middleware relative to an existing middleware name.
// Return error if the original middleware does not exist, or the middleware
// being added already exists.
func (s *DeserializeStep) Insert(m DeserializeMiddleware, relativeTo string, pos RelativePosition) error {
	return s.group.Insert(m, relativeTo, pos)
}

// Swap removes the middleware by name, replacing it with the new middleware.
// Returns error if the original middleware doesn't exist.
func (s *DeserializeStep) Swap(name string, m DeserializeMiddleware) error {
	return s.group.Swap(name, m)
}

// Remove removes the middleware by name. Returns error if the middleware
// doesn't exist.
func (s *DeserializeStep) Remove(name string) error {
	return s.group.Remove(name)
}

type deserializeWrapHandler struct {
	Next Handler
}

var _ DeserializeHandler = (*deserializeWrapHandler)(nil)

// Implements DeserializeHandler, converts types and delegates to underlying
// generic handler.
func (w deserializeWrapHandler) HandleDeserialize(ctx context.Context, in DeserializeInput) (
	out DeserializeOutput, err error,
) {
	resp, err := w.Next.Handle(ctx, in.Request)
	if err != nil {
		return DeserializeOutput{}, err
	}

	return DeserializeOutput{
		RawResponse: resp,
	}, nil
}

type decorateDeserializeHandler struct {
	Next DeserializeHandler
	With DeserializeMiddleware
}

var _ DeserializeHandler = (*decorateDeserializeHandler)(nil)

func (h decorateDeserializeHandler) HandleDeserialize(ctx context.Context, in DeserializeInput) (
	out DeserializeOutput, err error,
) {
	return h.With.HandleDeserialize(ctx, in, h.Next)
}
