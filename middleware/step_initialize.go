package middleware

import "context"

// InitializeInput provides the input parameters for serializing input for a
// handler.
type InitializeInput struct {
	Parameters interface{}
}

// InitializeOutput provides the result of initialize handler middleware stack.
type InitializeOutput struct {
	Result interface{}
}

// InitializeHandler provides the interface for handling the initialization
// step of a middleware stack. Wraps the underlying handler.
type InitializeHandler interface {
	HandleInitialize(ctx context.Context, in InitializeInput) (
		out InitializeOutput, err error,
	)
}

// InitializeMiddleware provides the interface for middleware specific to the
// initialize step.
type InitializeMiddleware interface {
	Name() string
	HandleInitialize(ctx context.Context, in InitializeInput, next InitializeHandler) (
		out InitializeOutput, err error,
	)
}

// InitializeMiddlewareFunc wraps a function to satisfy the
// InitializeMiddleware interface.
type InitializeMiddlewareFunc func(ctx context.Context, in InitializeInput, next InitializeHandler) (
	out InitializeOutput, err error,
)

var _ InitializeMiddleware = (InitializeMiddlewareFunc)(nil)

// Name returns an empty string that will be replaced for a stub value when
// added to the InitializeStep.
func (f InitializeMiddlewareFunc) Name() string { return "" }

// HandleInitialize invokes the function with passed in parameters. Returning
// the result or error.
//
// Implements InitializeMiddleware interface.
func (f InitializeMiddlewareFunc) HandleInitialize(ctx context.Context, in InitializeInput, next InitializeHandler) (
	out InitializeOutput, err error,
) {
	return f(ctx, in, next)
}

// InitializeStep provides the ordered grouping of InitializeMiddleware to be
// invoked on an handler.
type InitializeStep struct {
	group orderedGroup
}

var _ Middleware = (*InitializeStep)(nil)

// Name returns the name of the initialization step.
func (s *InitializeStep) Name() string {
	return "Initialize stack step"
}

// HandleMiddleware invokes the middleware by decorating the next handler
// provided. Returns the result of the middleware and handler being invoked.
//
// Implements Middleware interface.
func (s *InitializeStep) HandleMiddleware(ctx context.Context, in interface{}, next Handler) (
	out interface{}, err error,
) {
	order := s.group.GetOrder()

	var h InitializeHandler = initializeWrapHandler{Next: next}
	for i := len(order); i >= 0; i-- {
		h = decorateInitializeHandler{
			Next: h,
			With: order[i].(InitializeMiddleware),
		}
	}

	sIn := InitializeInput{
		Parameters: in,
	}

	res, err := h.HandleInitialize(ctx, sIn)
	if err != nil {
		return nil, err
	}

	return res.Result, nil
}

// Add injects the middleware to the relative position of the middleware group.
// Returns an error if the middleware already exists.
func (s *InitializeStep) Add(m InitializeMiddleware, pos RelativePosition) error {
	return s.group.Add(m, pos)
}

// Insert injects the middleware relative to an existing middleware name.
// Return error if the original middleware does not exist, or the middleware
// being added already exists.
func (s *InitializeStep) Insert(m InitializeMiddleware, relativeTo string, pos RelativePosition) error {
	return s.group.Insert(m, relativeTo, pos)
}

// Swap removes the middleware by name, replacing it with the new middleware.
// Returns error if the original middleware doesn't exist.
func (s *InitializeStep) Swap(name string, m InitializeMiddleware) error {
	return s.group.Swap(name, m)
}

// Remove removes the middleware by name. Returns error if the middleware
// doesn't exist.
func (s *InitializeStep) Remove(name string) error {
	return s.group.Remove(name)
}

type initializeWrapHandler struct {
	Next Handler
}

var _ InitializeHandler = (*initializeWrapHandler)(nil)

// Implements InitializeHandler, converts types and delegates to underlying
// generic handler.
func (w initializeWrapHandler) HandleInitialize(ctx context.Context, in InitializeInput) (
	out InitializeOutput, err error,
) {
	res, err := w.Next.Handle(ctx, in.Parameters)
	if err != nil {
		return InitializeOutput{}, err
	}

	return InitializeOutput{
		Result: res,
	}, nil
}

type decorateInitializeHandler struct {
	Next InitializeHandler
	With InitializeMiddleware
}

var _ InitializeHandler = (*decorateInitializeHandler)(nil)

func (h decorateInitializeHandler) HandleInitialize(ctx context.Context, in InitializeInput) (
	out InitializeOutput, err error,
) {
	return h.With.HandleInitialize(ctx, in, h.Next)
}
