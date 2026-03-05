package httpbinding

import (
	"fmt"
	"strconv"
	"time"

	"github.com/aws/smithy-go"
	smithytime "github.com/aws/smithy-go/time"
	"github.com/aws/smithy-go/traits"
)

// resolveTimestampFormat returns the timestamp format for the given schema,
// falling back to the provided default if no @timestampFormat trait is present.
func resolveTimestampFormat(schema *smithy.Schema, defaultFormat string) string {
	if tf, ok := smithy.SchemaTrait[*traits.TimestampFormat](schema); ok {
		return tf.Format
	}
	return defaultFormat
}

// formatTimestamp formats a time value using the resolved timestamp format.
func formatTimestamp(schema *smithy.Schema, defaultFormat string, v time.Time) string {
	switch resolveTimestampFormat(schema, defaultFormat) {
	case "http-date":
		return smithytime.FormatHTTPDate(v)
	case "date-time":
		return smithytime.FormatDateTime(v)
	case "epoch-seconds":
		return fmt.Sprintf("%g", smithytime.FormatEpochSeconds(v))
	default:
		return smithytime.FormatHTTPDate(v)
	}
}

// parseTimestamp parses a time string using the resolved timestamp format.
func parseTimestamp(schema *smithy.Schema, defaultFormat string, s string) (time.Time, error) {
	switch resolveTimestampFormat(schema, defaultFormat) {
	case "http-date":
		return smithytime.ParseHTTPDate(s)
	case "date-time":
		return smithytime.ParseDateTime(s)
	case "epoch-seconds":
		v, err := strconv.ParseFloat(s, 64)
		if err != nil {
			return time.Time{}, fmt.Errorf("parse epoch-seconds %q: %w", s, err)
		}
		return smithytime.ParseEpochSeconds(v), nil
	default:
		return smithytime.ParseHTTPDate(s)
	}
}
