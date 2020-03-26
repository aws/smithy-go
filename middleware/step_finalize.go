package middleware

import "context"

// FinalizeInput provides the input parameters for serializing input for a
// handler.
type FinalizeInput struct {
	Request interface{}
}

// FinalizeOutput provides the result of finalize handler middleware stack.
type FinalizeOutput struct {
	Result interface{}
}

// FinalizeHandler provides the interface for handling the initialization
// step of a middleware stack. Wraps the underlying handler.
type FinalizeHandler interface {
	HandleFinalize(ctx context.Context, in FinalizeInput) (
		out FinalizeOutput, err error,
	)
}

// FinalizeMiddleware provides the interface for middleware specific to the
// finalize step.
type FinalizeMiddleware interface {
	Name() string
	HandleFinalize(ctx context.Context, in FinalizeInput, next FinalizeHandler) (
		out FinalizeOutput, err error,
	)
}

// FinalizeMiddlewareFunc wraps a function to satisfy the
// FinalizeMiddleware interface.
type FinalizeMiddlewareFunc func(ctx context.Context, in FinalizeInput, next FinalizeHandler) (
	out FinalizeOutput, err error,
)

var _ FinalizeMiddleware = (FinalizeMiddlewareFunc)(nil)

// Name returns an empty string that will be replaced for a stub value when
// added to the FinalizeStep.
func (f FinalizeMiddlewareFunc) Name() string { return "" }

// HandleFinalize invokes the function with passed in parameters. Returning
// the result or error.
//
// Implements FinalizeMiddleware interface.
func (f FinalizeMiddlewareFunc) HandleFinalize(ctx context.Context, in FinalizeInput, next FinalizeHandler) (
	out FinalizeOutput, err error,
) {
	return f(ctx, in, next)
}

// FinalizeStep provides the ordered grouping of FinalizeMiddleware to be
// invoked on an handler.
type FinalizeStep struct {
	group orderedGroup
}

var _ Middleware = (*FinalizeStep)(nil)

// Name returns the name of the initialization step.
func (s *FinalizeStep) Name() string {
	return "Finalize stack step"
}

// HandleMiddleware invokes the middleware by decorating the next handler
// provided. Returns the result of the middleware and handler being invoked.
//
// Implements Middleware interface.
func (s *FinalizeStep) HandleMiddleware(ctx context.Context, in interface{}, next Handler) (
	out interface{}, err error,
) {
	order := s.group.GetOrder()

	var h FinalizeHandler = finalizeWrapHandler{Next: next}
	for i := len(order); i >= 0; i-- {
		h = decorateFinalizeHandler{
			Next: h,
			With: order[i].(FinalizeMiddleware),
		}
	}

	sIn := FinalizeInput{
		Request: in,
	}

	res, err := h.HandleFinalize(ctx, sIn)
	if err != nil {
		return nil, err
	}

	return res.Result, nil
}

// Add injects the middleware to the relative position of the middleware group.
// Returns an error if the middleware already exists.
func (s *FinalizeStep) Add(m FinalizeMiddleware, pos RelativePosition) error {
	return s.group.Add(m, pos)
}

// Insert injects the middleware relative to an existing middleware name.
// Return error if the original middleware does not exist, or the middleware
// being added already exists.
func (s *FinalizeStep) Insert(m FinalizeMiddleware, relativeTo string, pos RelativePosition) error {
	return s.group.Insert(m, relativeTo, pos)
}

// Swap removes the middleware by name, replacing it with the new middleware.
// Returns error if the original middleware doesn't exist.
func (s *FinalizeStep) Swap(name string, m FinalizeMiddleware) error {
	return s.group.Swap(name, m)
}

// Remove removes the middleware by name. Returns error if the middleware
// doesn't exist.
func (s *FinalizeStep) Remove(name string) error {
	return s.group.Remove(name)
}

type finalizeWrapHandler struct {
	Next Handler
}

var _ FinalizeHandler = (*finalizeWrapHandler)(nil)

// Implements FinalizeHandler, converts types and delegates to underlying
// generic handler.
func (w finalizeWrapHandler) HandleFinalize(ctx context.Context, in FinalizeInput) (
	out FinalizeOutput, err error,
) {
	res, err := w.Next.Handle(ctx, in.Request)
	if err != nil {
		return FinalizeOutput{}, err
	}

	return FinalizeOutput{
		Result: res,
	}, nil
}

type decorateFinalizeHandler struct {
	Next FinalizeHandler
	With FinalizeMiddleware
}

var _ FinalizeHandler = (*decorateFinalizeHandler)(nil)

func (h decorateFinalizeHandler) HandleFinalize(ctx context.Context, in FinalizeInput) (
	out FinalizeOutput, err error,
) {
	return h.With.HandleFinalize(ctx, in, h.Next)
}
