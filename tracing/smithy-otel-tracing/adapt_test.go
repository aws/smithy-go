package smithyoteltracing

import (
	"fmt"
	"testing"

	"github.com/aws/smithy-go/tracing"
	otelattribute "go.opentelemetry.io/otel/attribute"
	otelcodes "go.opentelemetry.io/otel/codes"
	oteltrace "go.opentelemetry.io/otel/trace"
)

func TestToOTELSpanKind(t *testing.T) {
	for _, tt := range []struct {
		In     tracing.SpanKind
		Expect oteltrace.SpanKind
	}{
		{tracing.SpanKindClient, oteltrace.SpanKindClient},
		{tracing.SpanKindServer, oteltrace.SpanKindServer},
		{tracing.SpanKindProducer, oteltrace.SpanKindProducer},
		{tracing.SpanKindConsumer, oteltrace.SpanKindConsumer},
		{tracing.SpanKindInternal, oteltrace.SpanKindInternal},
		{tracing.SpanKind(-1), oteltrace.SpanKindInternal},
	} {
		name := fmt.Sprintf("%v -> %v", tt.In, tt.Expect)
		t.Run(name, func(t *testing.T) {
			actual := toOTELSpanKind(tt.In)
			if tt.Expect != actual {
				t.Errorf("%v != %v", tt.Expect, actual)
			}
		})
	}
}

func TestToOTELSpanStatus(t *testing.T) {
	for _, tt := range []struct {
		In     tracing.SpanStatus
		Expect otelcodes.Code
	}{
		{tracing.SpanStatusOK, otelcodes.Ok},
		{tracing.SpanStatusError, otelcodes.Error},
		{tracing.SpanStatusUnset, otelcodes.Unset},
		{tracing.SpanStatus(-1), otelcodes.Unset},
	} {
		name := fmt.Sprintf("%v -> %v", tt.In, tt.Expect)
		t.Run(name, func(t *testing.T) {
			actual := toOTELSpanStatus(tt.In)
			if tt.Expect != actual {
				t.Errorf("%v != %v", tt.Expect, actual)
			}
		})
	}
}

type stringer struct{}

func (s stringer) String() string {
	return "stringer"
}

type notstringer struct{}

func TestToOTELKeyValue(t *testing.T) {
	for _, tt := range []struct {
		K, V   any
		Expect otelattribute.KeyValue
	}{
		{1, "asdf", otelattribute.String("1", "asdf")},               // non-string key
		{"key", stringer{}, otelattribute.String("key", "stringer")}, // stringer
		// unsupported value type
		{"key", notstringer{}, otelattribute.String("key", "smithyoteltracing.notstringer{}")},
		{"key", true, otelattribute.Bool("key", true)},
		{"key", []bool{true, false}, otelattribute.BoolSlice("key", []bool{true, false})},
		{"key", int(1), otelattribute.Int("key", 1)},
		{"key", []int{1, 2}, otelattribute.IntSlice("key", []int{1, 2})},
		{"key", int64(1), otelattribute.Int64("key", 1)},
		{"key", []int64{1, 2}, otelattribute.Int64Slice("key", []int64{1, 2})},
		{"key", float64(1), otelattribute.Float64("key", 1)},
		{"key", []float64{1, 2}, otelattribute.Float64Slice("key", []float64{1, 2})},
		{"key", "value", otelattribute.String("key", "value")},
		{"key", []string{"v1", "v2"}, otelattribute.StringSlice("key", []string{"v1", "v2"})},
	} {
		name := fmt.Sprintf("(%v, %v) -> %v", tt.K, tt.V, tt.Expect)
		t.Run(name, func(t *testing.T) {
			actual := toOTELKeyValue(tt.K, tt.V)
			if tt.Expect != actual {
				t.Errorf("%v != %v", tt.Expect, actual)
			}
		})
	}
}
