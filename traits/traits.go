// Package traits defines representations of Smithy IDL traits that appear in
// code-generated schemas or are returned by methods on code-generated types.
package traits

import smithy "github.com/aws/smithy-go"

// Sensitive represents smithy.api#sensitive.
type Sensitive struct{}

// TraitID identifies the trait.
func (*Sensitive) TraitID() smithy.ShapeID { return smithy.ShapeID{Namespace: "smithy.api", Name: "sensitive"} }

// Error represents smithy.api#error.
type Error struct {
	Type string
}

// TraitID identifies the trait.
func (*Error) TraitID() smithy.ShapeID { return smithy.ShapeID{Namespace: "smithy.api", Name: "error"} }

// Retryable represents smithy.api#retryable. It is returned by the
// RetryableTrait method generated on modeled error types rather than included
// in schemas, since the trait does not affect (de)serialization.
//
// Fields are added as the trait gains properties, so construct values with
// keyed fields.
type Retryable struct {
	// Throttling indicates that the error is a throttling error.
	Throttling bool
}

// EventHeader represents smithy.api#eventHeader.
type EventHeader struct{}

// TraitID identifies the trait.
func (*EventHeader) TraitID() smithy.ShapeID { return smithy.ShapeID{Namespace: "smithy.api", Name: "eventHeader"} }

// EventPayload represents smithy.api#eventPayload.
type EventPayload struct{}

// TraitID identifies the trait.
func (*EventPayload) TraitID() smithy.ShapeID { return smithy.ShapeID{Namespace: "smithy.api", Name: "eventPayload"} }

// Streaming represents smithy.api#streaming.
type Streaming struct{}

// TraitID identifies the trait.
func (*Streaming) TraitID() smithy.ShapeID { return smithy.ShapeID{Namespace: "smithy.api", Name: "streaming"} }

// HostLabel represents smithy.api#hostLabel.
type HostLabel struct{}

// TraitID identifies the trait.
func (*HostLabel) TraitID() smithy.ShapeID { return smithy.ShapeID{Namespace: "smithy.api", Name: "hostLabel"} }

// ContextParam represents smithy.rules#contextParam.
type ContextParam struct{}

// TraitID identifies the trait.
func (*ContextParam) TraitID() smithy.ShapeID { return smithy.ShapeID{Namespace: "smithy.rules", Name: "contextParam"} }

// AWSQueryError represents aws.protocols#awsQueryError.
type AWSQueryError struct {
	ErrorCode  string
	StatusCode int
}

// TraitID identifies the trait.
func (*AWSQueryError) TraitID() smithy.ShapeID { return smithy.ShapeID{Namespace: "aws.protocols", Name: "awsQueryError"} }

// EC2QueryName represents aws.protocols#ec2QueryName.
type EC2QueryName struct {
	Name string
}

// TraitID identifies the trait.
func (*EC2QueryName) TraitID() smithy.ShapeID { return smithy.ShapeID{Namespace: "aws.protocols", Name: "ec2QueryName"} }

// AWSQueryCompatible represents aws.protocols#awsQueryCompatible.
type AWSQueryCompatible struct{}

// TraitID identifies the trait.
func (*AWSQueryCompatible) TraitID() smithy.ShapeID { return smithy.ShapeID{Namespace: "aws.protocols", Name: "awsQueryCompatible"} }

// UnitShape is a synthetic trait applied to input/output shapes that were
// backfilled from Unit. It indicates the shape has no defined members and
// should be treated as absent for protocol serialization purposes.
type UnitShape struct{}

// TraitID identifies the trait.
func (*UnitShape) TraitID() smithy.ShapeID { return smithy.ShapeID{Namespace: "smithy.go", Name: "unitShape"} }
