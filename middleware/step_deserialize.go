package middleware

import "context"

// DeserializeInput provides the input parameters for the DeserializeInput to
// consume. DeserializeMiddleware should not modify the Request, and instead
// forward it along to the next DeserializeHandler.
type DeserializeInput struct {
	Request interface{}
}

// DeserializeOutput provides the result returned by the next
// DeserializeHandler. The DeserializeMiddleware should deserailize the
// RawResponse into a Result that can be consumed by middleware higher up in
// the stack.
type DeserializeOutput struct {
	RawResponse interface{}
	Result      interface{}
}

// DeserializeHandler provides the interface for the next handler the
// DeserializeMiddleware will call in the middleware chain.
type DeserializeHandler interface {
	HandleDeserialize(ctx context.Context, in DeserializeInput) (
		out DeserializeOutput, metadata Metadata, err error,
	)
}

// DeserializeMiddleware provides the interface for middleware specific to the
// serialize step. Delegates to the next DeserializeHandler for further
// processing.
type DeserializeMiddleware interface {
	// Unique ID for the middleware in the DeserializeStep. The step does not
	// allow duplicate IDs.
	ID() string

	// Invokes the middleware behavior which must delegate to the next handler
	// for the middleware chain to continue. The method must return a result or
	// error to its caller.
	HandleDeserialize(ctx context.Context, in DeserializeInput, next DeserializeHandler) (
		out DeserializeOutput, metadata Metadata, err error,
	)
}

// DeserializeMiddlewareFunc returns a DeserializeMiddleware with the unique ID
// provided, and the func to be invoked.
func DeserializeMiddlewareFunc(id string, fn func(context.Context, DeserializeInput, DeserializeHandler) (DeserializeOutput, Metadata, error)) DeserializeMiddleware {
	return deserializeMiddlewareFunc{
		id: id,
		fn: fn,
	}
}

type deserializeMiddlewareFunc struct {
	// Unique ID for the middleware.
	id string

	// Middleware function to be called.
	fn func(context.Context, DeserializeInput, DeserializeHandler) (
		DeserializeOutput, Metadata, error,
	)
}

// ID returns the unique ID for the middleware.
func (s deserializeMiddlewareFunc) ID() string { return s.id }

// HandleDeserialize invokes the middleware Fn.
func (s deserializeMiddlewareFunc) HandleDeserialize(ctx context.Context, in DeserializeInput, next DeserializeHandler) (
	out DeserializeOutput, metadata Metadata, err error,
) {
	return s.fn(ctx, in, next)
}

var _ DeserializeMiddleware = (deserializeMiddlewareFunc{})

// DeserializeStep provides the ordered grouping of DeserializeMiddleware to be
// invoked on an handler.
type DeserializeStep struct {
	ids *orderedIDs
}

// NewDeserializeStep returns an DeserializeStep ready to have middleware for
// initialization added to it.
func NewDeserializeStep() *DeserializeStep {
	return &DeserializeStep{
		ids: newOrderedIDs(),
	}
}

var _ Middleware = (*DeserializeStep)(nil)

// ID returns the unique id of the step as a middleware.
func (s *DeserializeStep) ID() string {
	return "Deserialize stack step"
}

// HandleMiddleware invokes the middleware by decorating the next handler
// provided. Returns the result of the middleware and handler being invoked.
//
// Implements Middleware interface.
func (s *DeserializeStep) HandleMiddleware(ctx context.Context, in interface{}, next Handler) (
	out interface{}, metadata Metadata, err error,
) {
	order := s.ids.GetOrder()

	var h DeserializeHandler = deserializeWrapHandler{Next: next}
	for i := len(order) - 1; i >= 0; i-- {
		h = decoratedDeserializeHandler{
			Next: h,
			With: order[i].(DeserializeMiddleware),
		}
	}

	sIn := DeserializeInput{
		Request: in,
	}

	res, metadata, err := h.HandleDeserialize(ctx, sIn)
	return res.Result, metadata, err
}

// Add injects the middleware to the relative position of the middleware group.
// Returns an error if the middleware already exists.
func (s *DeserializeStep) Add(m DeserializeMiddleware, pos RelativePosition) error {
	return s.ids.Add(m, pos)
}

// Insert injects the middleware relative to an existing middleware id.
// Return error if the original middleware does not exist, or the middleware
// being added already exists.
func (s *DeserializeStep) Insert(m DeserializeMiddleware, relativeTo string, pos RelativePosition) error {
	return s.ids.Insert(m, relativeTo, pos)
}

// Swap removes the middleware by id, replacing it with the new middleware.
// Returns the middleware removed, or error if the middleware to be removed
// doesn't exist.
func (s *DeserializeStep) Swap(id string, m DeserializeMiddleware) (DeserializeMiddleware, error) {
	removed, err := s.ids.Swap(id, m)
	if err != nil {
		return nil, err
	}

	return removed.(DeserializeMiddleware), nil
}

// Remove removes the middleware by id. Returns error if the middleware
// doesn't exist.
func (s *DeserializeStep) Remove(id string) error {
	return s.ids.Remove(id)
}

// Clear removes all middleware in the step.
func (s *DeserializeStep) Clear() {
	s.ids.Clear()
}

type deserializeWrapHandler struct {
	Next Handler
}

var _ DeserializeHandler = (*deserializeWrapHandler)(nil)

// Implements DeserializeHandler, converts types and delegates to underlying
// generic handler.
func (w deserializeWrapHandler) HandleDeserialize(ctx context.Context, in DeserializeInput) (
	out DeserializeOutput, metadata Metadata, err error,
) {
	resp, metadata, err := w.Next.Handle(ctx, in.Request)
	return DeserializeOutput{
		RawResponse: resp,
	}, metadata, err
}

type decoratedDeserializeHandler struct {
	Next DeserializeHandler
	With DeserializeMiddleware
}

var _ DeserializeHandler = (*decoratedDeserializeHandler)(nil)

func (h decoratedDeserializeHandler) HandleDeserialize(ctx context.Context, in DeserializeInput) (
	out DeserializeOutput, metadata Metadata, err error,
) {
	ctx = RecordMiddleware(ctx, h.With.ID())
	return h.With.HandleDeserialize(ctx, in, h.Next)
}