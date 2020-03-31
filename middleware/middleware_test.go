package middleware

import (
	"context"
	"fmt"
	"reflect"
	"testing"
)

var _ Handler = (HandlerFunc)(nil)
var _ Handler = (decoratedHandler{})

type mockMiddleware struct {
	id int
}

func (m mockMiddleware) ID() string {
	return fmt.Sprintf("mock middleware %d", m.id)
}

func (m mockMiddleware) HandleMiddleware(ctx context.Context, input interface{}, next Handler) (
	output interface{}, err error,
) {
	return next.Handle(ctx, input)
}

type mockHandler struct {
	calledMiddleware []string
}

func (m *mockHandler) Handle(ctx context.Context, input interface{}) (
	output interface{}, err error,
) {
	m.calledMiddleware = GetMiddlewareIDs(ctx)
	return nil, nil
}

func TestDecorateHandler(t *testing.T) {
	mockHandler := &mockHandler{}
	h := DecorateHandler(
		mockHandler,
		mockMiddleware{id: 0},
		mockMiddleware{id: 1},
		mockMiddleware{id: 2},
	)

	_, err := h.Handle(context.Background(), struct{}{})
	if err != nil {
		t.Fatalf("expect no error, got %v", err)
	}

	if e, a := 3, len(mockHandler.calledMiddleware); e != a {
		t.Errorf("expect %v middleware, got %v", e, a)
	}

	expect := []string{
		"mock middleware 0",
		"mock middleware 1",
		"mock middleware 2",
	}
	if e, a := expect, mockHandler.calledMiddleware; !reflect.DeepEqual(e, a) {
		t.Errorf("expect:\n%v\nactual:\n%v", e, a)
	}
}
