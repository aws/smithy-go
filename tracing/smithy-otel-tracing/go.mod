module github.com/aws/smithy-go/tracing/smithy-otel-tracing

go 1.22

require (
	github.com/aws/smithy-go v1.20.4
	go.opentelemetry.io/otel v1.28.0
	go.opentelemetry.io/otel/trace v1.28.0
)

replace github.com/aws/smithy-go => ../../
