module github.com/aws/smithy-go/tracing/smithyoteltracing

go 1.22

require (
	github.com/aws/smithy-go v1.20.4
	go.opentelemetry.io/otel v1.29.0
	go.opentelemetry.io/otel/trace v1.29.0
)

replace github.com/aws/smithy-go => ../../
