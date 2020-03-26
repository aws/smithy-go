package middleware

import (
	"context"
)

// Stack provides the middleweare stack split into distinct steps.
type Stack struct {
	Initialize  *InitializeStep
	Serialize   *SerializeStep
	Build       *BuildStep
	Finalize    *FinalizeStep
	Deserialize *DeserializeStep
}

// NewStack returns an initialize empty stack.
func NewStack() *Stack {
	return &Stack{
		Initialize:  &InitializeStep{},
		Serialize:   &SerializeStep{},
		Build:       &BuildStep{},
		Finalize:    &FinalizeStep{},
		Deserialize: &DeserializeStep{},
	}
}

// HandleMiddleware invokes the middleware stack decorating the next handler.
// Each step of stack will be invoked in order before calling the next step.
// With the next handler call last.
func (s Stack) HandleMiddleware(ctx context.Context, input interface{}, next Handler) (
	output interface{}, err error,
) {
	h := DecorateHandler(next,
		s.Initialize,
		s.Serialize,
		s.Build,
		s.Finalize,
		s.Deserialize,
	)

	return h.Handle(ctx, input)
}
