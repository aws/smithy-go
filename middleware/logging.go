package middleware

import (
	"context"

	"github.com/awslabs/smithy-go/logging"
)

// loggerKey is the context value key for which the logger is associated with.
type loggerKey struct {}

// GetLogger takes a context to retrieve a Logger from. If no logger is present on the context a logging.Noop logger
// is returned. If the logger retrieved from context supports the ContextLogger interface, the context will be passed
// to the WithContext method and the resulting logger will be returned. Otherwise the stored logger is returned as is.
func GetLogger(ctx context.Context) logging.Logger {
	logger, ok := ctx.Value(loggerKey{}).(logging.Logger)
	if !ok || logger == nil {
		return logging.Noop{}
	}

	return logging.WithContext(ctx, logger)
}

// SetLogger sets the provided logger value on the provided ctx.
func SetLogger(ctx context.Context, logger logging.Logger) context.Context {
	return context.WithValue(ctx, loggerKey{}, logger)
}
