package http

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"testing"
	"time"

	smithy "github.com/aws/smithy-go"
)

func TestClientHandler_Handle(t *testing.T) {
	cases := map[string]struct {
		Context   context.Context
		Client    ClientDo
		ExpectErr func(error) error
	}{
		"no error": {
			Context: context.Background(),
			Client: ClientDoFunc(func(*http.Request) (*http.Response, error) {
				return &http.Response{}, nil
			}),
		},
		"send error": {
			Context: context.Background(),
			Client: ClientDoFunc(func(*http.Request) (*http.Response, error) {
				return nil, fmt.Errorf("some error")
			}),
			ExpectErr: func(err error) error {
				var sendError *RequestSendError
				if !errors.As(err, &sendError) {
					return fmt.Errorf("expect error to be %T, %v", sendError, err)
				}

				var cancelError *smithy.CanceledError
				if errors.As(err, &cancelError) {
					return fmt.Errorf("expect error to not be %T, %v", cancelError, err)
				}

				return nil
			},
		},
		"canceled error": {
			Context: func() context.Context {
				ctx, fn := context.WithCancel(context.Background())
				fn()
				return ctx
			}(),
			Client: ClientDoFunc(func(*http.Request) (*http.Response, error) {
				return nil, fmt.Errorf("some error")
			}),
			ExpectErr: func(err error) error {
				var sendError *RequestSendError
				if errors.As(err, &sendError) {
					return fmt.Errorf("expect error to not be %T, %v", sendError, err)
				}

				var cancelError *smithy.CanceledError
				if !errors.As(err, &cancelError) {
					return fmt.Errorf("expect error to be %T, %v", cancelError, err)
				}
				if !errors.Is(cancelError.Err, context.Canceled) {
					return fmt.Errorf("expect underlying error to be context.Canceled, got %v", cancelError.Err)
				}
				return nil
			},
		},
		"context timeout": {
			Context: func() context.Context {
				ctx, fn := context.WithTimeout(context.Background(), 5*time.Millisecond)
				fn()
				return ctx
			}(),
			Client: ClientDoFunc(func(req *http.Request) (*http.Response, error) {
				select {
				case <-time.After(50 * time.Millisecond):
					return &http.Response{}, nil
				case <-req.Context().Done():
					return nil, req.Context().Err()
				}
			}),
			ExpectErr: func(err error) error {
				var cancelError *smithy.CanceledError
				if !errors.As(err, &cancelError) {
					return fmt.Errorf("expect error to be %T, %v", cancelError, err)
				}
				if !errors.Is(cancelError.Err, context.Canceled) {
					return fmt.Errorf("expect underlying error to be context.Canceled, got %v", cancelError.Err)
				}
				return nil
			},
		},
		"context deadline": {
			Context: func() context.Context {
				ctx, fn := context.WithDeadline(context.Background(), time.Now().Add(50*time.Millisecond))
				fn()
				return ctx
			}(),
			Client: ClientDoFunc(func(req *http.Request) (*http.Response, error) {
				select {
				case <-time.After(500 * time.Millisecond):
					return &http.Response{}, nil
				case <-req.Context().Done():
					return nil, req.Context().Err()
				}
			}),
			ExpectErr: func(err error) error {
				var cancelError *smithy.CanceledError
				if !errors.As(err, &cancelError) {
					return fmt.Errorf("expect error to be %T, %v", cancelError, err)
				}
				if !errors.Is(cancelError.Err, context.Canceled) {
					return fmt.Errorf("expect underlying error to be context.Canceled, got %v", cancelError.Err)
				}
				return nil
			},
		},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			handler := NewClientHandler(c.Client)
			resp, _, err := handler.Handle(c.Context, NewStackRequest())

			if c.ExpectErr != nil {
				if err == nil {
					t.Fatalf("expect error, got none")
				}

				if err = c.ExpectErr(err); err != nil {
					t.Fatalf("expect error match failed, %v", err)
				}
				return
			}
			if err != nil {
				t.Fatalf("expect no error, got %v", err)
			}

			if _, ok := resp.(*Response); !ok {
				t.Fatalf("expect Response type, got %T", resp)
			}
		})
	}

}
