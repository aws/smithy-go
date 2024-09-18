module github.com/aws/smithy-go/metrics/smithyotelmetrics

go 1.22

require (
	github.com/aws/smithy-go v1.20.4
	go.opentelemetry.io/otel v1.29.0
	go.opentelemetry.io/otel/metric v1.29.0
)

replace github.com/aws/smithy-go => ../../
