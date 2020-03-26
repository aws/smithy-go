package middleware

import "context"

// BuildInput provides the input parameters for serializing input for a
// handler.
type BuildInput struct {
	Request interface{}
}

// BuildOutput provides the result of build handler middleware stack.
type BuildOutput struct {
	Result interface{}
}

// BuildHandler provides the interface for handling the initialization
// step of a middleware stack. Wraps the underlying handler.
type BuildHandler interface {
	HandleBuild(ctx context.Context, in BuildInput) (
		out BuildOutput, err error,
	)
}

// BuildMiddleware provides the interface for middleware specific to the
// build step.
type BuildMiddleware interface {
	Name() string
	HandleBuild(ctx context.Context, in BuildInput, next BuildHandler) (
		out BuildOutput, err error,
	)
}

// BuildMiddlewareFunc wraps a function to satisfy the
// BuildMiddleware interface.
type BuildMiddlewareFunc func(ctx context.Context, in BuildInput, next BuildHandler) (
	out BuildOutput, err error,
)

var _ BuildMiddleware = (BuildMiddlewareFunc)(nil)

// Name returns an empty string that will be replaced for a stub value when
// added to the BuildStep.
func (f BuildMiddlewareFunc) Name() string { return "" }

// HandleBuild invokes the function with passed in parameters. Returning
// the result or error.
//
// Implements BuildMiddleware interface.
func (f BuildMiddlewareFunc) HandleBuild(ctx context.Context, in BuildInput, next BuildHandler) (
	out BuildOutput, err error,
) {
	return f(ctx, in, next)
}

// BuildStep provides the ordered grouping of BuildMiddleware to be
// invoked on an handler.
type BuildStep struct {
	group orderedGroup
}

var _ Middleware = (*BuildStep)(nil)

// Name returns the name of the initialization step.
func (s *BuildStep) Name() string {
	return "Build stack step"
}

// HandleMiddleware invokes the middleware by decorating the next handler
// provided. Returns the result of the middleware and handler being invoked.
//
// Implements Middleware interface.
func (s *BuildStep) HandleMiddleware(ctx context.Context, in interface{}, next Handler) (
	out interface{}, err error,
) {
	order := s.group.GetOrder()

	var h BuildHandler = buildWrapHandler{Next: next}
	for i := len(order); i >= 0; i-- {
		h = decorateBuildHandler{
			Next: h,
			With: order[i].(BuildMiddleware),
		}
	}

	sIn := BuildInput{
		Request: in,
	}

	res, err := h.HandleBuild(ctx, sIn)
	if err != nil {
		return nil, err
	}

	return res.Result, nil
}

// Add injects the middleware to the relative position of the middleware group.
// Returns an error if the middleware already exists.
func (s *BuildStep) Add(m BuildMiddleware, pos RelativePosition) error {
	return s.group.Add(m, pos)
}

// Insert injects the middleware relative to an existing middleware name.
// Return error if the original middleware does not exist, or the middleware
// being added already exists.
func (s *BuildStep) Insert(m BuildMiddleware, relativeTo string, pos RelativePosition) error {
	return s.group.Insert(m, relativeTo, pos)
}

// Swap removes the middleware by name, replacing it with the new middleware.
// Returns error if the original middleware doesn't exist.
func (s *BuildStep) Swap(name string, m BuildMiddleware) error {
	return s.group.Swap(name, m)
}

// Remove removes the middleware by name. Returns error if the middleware
// doesn't exist.
func (s *BuildStep) Remove(name string) error {
	return s.group.Remove(name)
}

type buildWrapHandler struct {
	Next Handler
}

var _ BuildHandler = (*buildWrapHandler)(nil)

// Implements BuildHandler, converts types and delegates to underlying
// generic handler.
func (w buildWrapHandler) HandleBuild(ctx context.Context, in BuildInput) (
	out BuildOutput, err error,
) {
	res, err := w.Next.Handle(ctx, in.Request)
	if err != nil {
		return BuildOutput{}, err
	}

	return BuildOutput{
		Result: res,
	}, nil
}

type decorateBuildHandler struct {
	Next BuildHandler
	With BuildMiddleware
}

var _ BuildHandler = (*decorateBuildHandler)(nil)

func (h decorateBuildHandler) HandleBuild(ctx context.Context, in BuildInput) (
	out BuildOutput, err error,
) {
	return h.With.HandleBuild(ctx, in, h.Next)
}
