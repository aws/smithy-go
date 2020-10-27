package logging

import (
	"context"
	"io"
	"log"
)

type Classification string

const (
	Warn  Classification = "WARN"
	Debug Classification = "DEBUG"
)

// Logger is an interface for logging entries at certain classifications.
type Logger interface {
	// Logf is expected to support the standard fmt package "verbs".
	Logf(level Classification, format string, v ...interface{})
}

// ContextLogger is an optional interface a Logger implementation may expose that provides
// the ability to create context aware log entries.
type ContextLogger interface {
	WithContext(context.Context) Logger
}

// WithContext will pass the provided context to logger if it implements the ContextLogger interface and return the resulting
// logger. Otherwise the logger will be returned as is.
func WithContext(ctx context.Context, logger Logger) Logger {
	cl, ok := logger.(ContextLogger)
	if !ok {
		return logger
	}

	return cl.WithContext(ctx)
}

// Noop is a Logger implementation that simply does not perform any logging.
type Noop struct{}

func (n Noop) Logf(Classification, string, ...interface{}) {
	return
}

// StandardLogger is a Logger implementation that wraps the standard library logger, and delegates logging to it's
// Printf method.
type StandardLogger struct {
	Logger *log.Logger
}

// Logf logs the given classification and message to the underlying logger.
func (s StandardLogger) Logf(classification Classification, format string, v ...interface{}) {
	if len(classification) != 0 {
		format = string(classification) + " " + format
	}

	s.Logger.Printf(format, v...)
}

// NewStandardLogger returns a new StandardLogger
func NewStandardLogger(writer io.Writer) *StandardLogger {
	return &StandardLogger{
		Logger: log.New(writer, "SDK ", log.LstdFlags),
	}
}
