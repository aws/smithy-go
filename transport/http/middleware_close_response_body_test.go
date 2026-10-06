package http_test

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"

	"github.com/aws/smithy-go/logging"
	"github.com/aws/smithy-go/middleware"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

type errCloseBody struct {
	io.Reader
	closeErr error
}

func (e *errCloseBody) Close() error { return e.closeErr }

type errReadBody struct {
	readErr  error
	closeErr error
}

func (e *errReadBody) Read([]byte) (int, error) { return 0, e.readErr }
func (e *errReadBody) Close() error             { return e.closeErr }

func TestCloseResponseBodyLogsCloseError(t *testing.T) {
	closeErr := errors.New("synthetic-close-failure")
	logger := &mockLogger{}
	ctx := middleware.SetLogger(context.Background(), logger)

	resp := &smithyhttp.Response{
		Response: &http.Response{
			Body: &errCloseBody{
				Reader:   strings.NewReader(""),
				closeErr: closeErr,
			},
		},
	}

	smithyhttp.CloseResponseBody(ctx, resp, false, nil)

	got := logger.String()
	if !strings.Contains(got, "failed to close HTTP response body") {
		t.Fatalf("expected close warning in log, got %q", got)
	}
	if !strings.Contains(got, closeErr.Error()) {
		t.Fatalf("expected close error %q in log, got %q", closeErr.Error(), got)
	}
}

func TestCloseResponseBodyLogsDiscardError(t *testing.T) {
	readErr := errors.New("synthetic-discard-failure")
	logger := &mockLogger{}
	ctx := middleware.SetLogger(context.Background(), logger)

	resp := &smithyhttp.Response{
		Response: &http.Response{
			Body: &errReadBody{readErr: readErr},
		},
	}

	smithyhttp.CloseResponseBody(ctx, resp, false, nil)

	got := logger.String()
	if !strings.Contains(got, "failed to discard remaining HTTP response body") {
		t.Fatalf("expected discard warning in log, got %q", got)
	}
	if !strings.Contains(got, readErr.Error()) {
		t.Fatalf("expected discard error %q in log, got %q", readErr.Error(), got)
	}
}

var _ logging.Logger = (*mockLogger)(nil)
