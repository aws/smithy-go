package middleware

import (
	"context"
	"fmt"
)

// InitializeInput wraps the input parameters for the InitializeMiddlewares to
// consume. InitializeMiddleware may modify the parameter value before
// forwarding it along to the next InitializeHandler.
type InitializeInput struct {
	Parameters interface{}
}

// InitializeOutput provides the result returned by the next InitializeHandler.
type InitializeOutput struct {
	Result interface{}
}

// InitializeHandler provides the interface for the next handler the
// InitializeMiddleware will call in the middleware chain.
type InitializeHandler interface {
	HandleInitialize(ctx context.Context, in InitializeInput) (
		out InitializeOutput, metadata Metadata, err error,
	)
}

// InitializeMiddleware provides the interface for middleware specific to the
// initialize step. Delegates to the next InitializeHandler for further
// processing.
type InitializeMiddleware interface {
	// ID returns a unique ID for the middleware in the InitializeStep. The step does not
	// allow duplicate IDs.
	ID() string

	// HandleInitialize invokes the middleware behavior which must delegate to the next handler
	// for the middleware chain to continue. The method must return a result or
	// error to its caller.
	HandleInitialize(ctx context.Context, in InitializeInput, next InitializeHandler) (
		out InitializeOutput, metadata Metadata, err error,
	)
}

// InitializeMiddlewareFunc returns a InitializeMiddleware with the unique ID provided,
// and the func to be invoked.
func InitializeMiddlewareFunc(id string, fn func(context.Context, InitializeInput, InitializeHandler) (InitializeOutput, Metadata, error)) InitializeMiddleware {
	return initializeMiddlewareFunc{
		id: id,
		fn: fn,
	}
}

type initializeMiddlewareFunc struct {
	// Unique ID for the middleware.
	id string

	// Middleware function to be called.
	fn func(context.Context, InitializeInput, InitializeHandler) (
		InitializeOutput, Metadata, error,
	)
}

// ID returns the unique ID for the middleware.
func (s initializeMiddlewareFunc) ID() string { return s.id }

// HandleInitialize invokes the middleware Fn.
func (s initializeMiddlewareFunc) HandleInitialize(ctx context.Context, in InitializeInput, next InitializeHandler) (
	out InitializeOutput, metadata Metadata, err error,
) {
	return s.fn(ctx, in, next)
}

var _ InitializeMiddleware = (initializeMiddlewareFunc{})

// InitializeStep provides the ordered grouping of InitializeMiddleware to be
// invoked on a handler.
type InitializeStep struct {
	head *decoratedInitializeHandler
	tail *decoratedInitializeHandler
}

// NewInitializeStep returns an InitializeStep ready to have middleware for
// initialization added to it.
func NewInitializeStep() *InitializeStep {
	return &InitializeStep{}
}

var _ Middleware = (*InitializeStep)(nil)

// ID returns the unique ID of the step as a middleware.
func (s *InitializeStep) ID() string {
	return "Initialize stack step"
}

// HandleMiddleware invokes the middleware by decorating the next handler
// provided. Returns the result of the middleware and handler being invoked.
//
// Implements Middleware interface.
func (s *InitializeStep) HandleMiddleware(ctx context.Context, in interface{}, next Handler) (
	out interface{}, metadata Metadata, err error,
) {
	sIn := InitializeInput{
		Parameters: in,
	}

	wnext := &initializeWrapHandler{next}
	if s.head == nil {
		res, metadata, err := wnext.HandleInitialize(ctx, sIn)
		return res.Result, metadata, err
	}

	s.tail.Next = wnext
	res, metadata, err := s.head.HandleInitialize(ctx, sIn)
	return res.Result, metadata, err
}

// Get retrieves the middleware identified by id. If the middleware is not present, returns false.
func (s *InitializeStep) Get(id string) (InitializeMiddleware, bool) {
	return nil, false
}

// Add injects the middleware to the relative position of the middleware group.
//
// Add used to return an error but now it does not. Thus, its return value can
// be ignored.
func (s *InitializeStep) Add(m InitializeMiddleware, pos RelativePosition) error {
	if s.head == nil {
		s.head = &decoratedInitializeHandler{nil, m}
		s.tail = s.head
		return nil
	}

	if pos == Before {
		s.head = &decoratedInitializeHandler{s.head, m}
	} else {
		tail := &decoratedInitializeHandler{nil, m}
		s.tail.Next = tail
		s.tail = tail
	}

	return nil
}

// Insert injects the middleware relative to an existing middleware ID.
// Returns error if the original middleware does not exist, or the middleware
// being added already exists.
func (s *InitializeStep) Insert(m InitializeMiddleware, relativeTo string, pos RelativePosition) error {
	var prev, found *decoratedInitializeHandler
	for h := any(s.head); h != nil; h = s.head.Next {
		if f, ok := h.(*decoratedInitializeHandler); ok && f.With.ID() == m.ID() {
			found = f
			break
		}
		prev = h.(*decoratedInitializeHandler)
	}
	if found == nil {
		return fmt.Errorf("not found: %s", m.ID())
	}

	if pos == Before {
		if prev == nil { // at the front
			s.head = &decoratedInitializeHandler{s.head, m}
		} else { // somewhere in the middle
			prev.Next = &decoratedInitializeHandler{found, m}
		}
	} else {
		if found.Next == nil { // at the end
			tail := &decoratedInitializeHandler{nil, m}
			s.tail.Next = tail
			s.tail = tail
		} else { // somewhere in the middle
			found.Next = &decoratedInitializeHandler{found.Next, m}
		}
	}

	return nil
}

// Swap removes the middleware by id, replacing it with the new middleware.
// Returns the middleware removed, or error if the middleware to be removed
// doesn't exist.
func (s *InitializeStep) Swap(id string, m InitializeMiddleware) (InitializeMiddleware, error) {
	for h := any(s.head); h != nil; h = s.head.Next {
		if f, ok := h.(*decoratedInitializeHandler); ok && f.With.ID() == m.ID() {
			old := f.With.(InitializeMiddleware)
			f.With = m
			return old, nil
		}
	}
	return nil, fmt.Errorf("not found: %s", m.ID())
}

// Remove removes the middleware by id. Returns error if the middleware
// doesn't exist.
func (s *InitializeStep) Remove(id string) (InitializeMiddleware, error) {
	var prev, found *decoratedInitializeHandler
	for h := any(s.head); h != nil; h = s.head.Next {
		if f, ok := h.(*decoratedInitializeHandler); ok && f.With.ID() == id {
			found = f
			break
		}
		prev = h.(*decoratedInitializeHandler)
	}
	if found == nil {
		return nil, fmt.Errorf("not found: %s", id)
	}

	if s.head == s.tail { // it's the only one
		s.head = nil
		s.tail = nil
	} else if found == s.head { // at the front
		s.head = s.head.Next.(*decoratedInitializeHandler)
	} else if found == s.tail { // at the end
		prev.Next = nil
		s.tail = prev
	} else {
		prev.Next = found.Next // somewhere in the middle
	}

	return found.With, nil
}

// List returns a list of the middleware in the step.
func (s *InitializeStep) List() []string {
	var ids []string
	for h := any(s.head); h != nil; h = s.head.Next {
		if f, ok := h.(*decoratedInitializeHandler); ok {
			ids = append(ids, f.With.ID())
		}
	}
	return ids
}

// Clear removes all middleware in the step.
func (s *InitializeStep) Clear() {
	s.head = nil
	s.tail = nil
}

type initializeWrapHandler struct {
	Next Handler
}

var _ InitializeHandler = (*initializeWrapHandler)(nil)

// HandleInitialize implements InitializeHandler, converts types and delegates to underlying
// generic handler.
func (w initializeWrapHandler) HandleInitialize(ctx context.Context, in InitializeInput) (
	out InitializeOutput, metadata Metadata, err error,
) {
	res, metadata, err := w.Next.Handle(ctx, in.Parameters)
	return InitializeOutput{
		Result: res,
	}, metadata, err
}

type decoratedInitializeHandler struct {
	Next InitializeHandler
	With InitializeMiddleware
}

var _ InitializeHandler = (*decoratedInitializeHandler)(nil)

func (h decoratedInitializeHandler) HandleInitialize(ctx context.Context, in InitializeInput) (
	out InitializeOutput, metadata Metadata, err error,
) {
	return h.With.HandleInitialize(ctx, in, h.Next)
}

// InitializeHandlerFunc provides a wrapper around a function to be used as an initialize middleware handler.
type InitializeHandlerFunc func(context.Context, InitializeInput) (InitializeOutput, Metadata, error)

// HandleInitialize calls the wrapped function with the provided arguments.
func (i InitializeHandlerFunc) HandleInitialize(ctx context.Context, in InitializeInput) (InitializeOutput, Metadata, error) {
	return i(ctx, in)
}

var _ InitializeHandler = InitializeHandlerFunc(nil)
