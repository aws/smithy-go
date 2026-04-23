// Package traits defines representations of Smithy IDL traits that appear in
// code-generated schemas.
package traits

// Sensitive represents smithy.api#sensitive.
type Sensitive struct{}

// TraitID identifies the trait.
func (*Sensitive) TraitID() string { return "smithy.api#sensitive" }

// EventHeader represents smithy.api#eventHeader.
type EventHeader struct{}

// TraitID identifies the trait.
func (*EventHeader) TraitID() string { return "smithy.api#eventHeader" }

// EventPayload represents smithy.api#eventPayload.
type EventPayload struct{}

// TraitID identifies the trait.
func (*EventPayload) TraitID() string { return "smithy.api#eventPayload" }

// Streaming represents smithy.api#streaming.
type Streaming struct{}

// TraitID identifies the trait.
func (*Streaming) TraitID() string { return "smithy.api#streaming" }

// HostLabel represents smithy.api#hostLabel.
type HostLabel struct{}

// TraitID identifies the trait.
func (*HostLabel) TraitID() string { return "smithy.api#hostLabel" }

// ContextParam represents smithy.rules#contextParam.
type ContextParam struct{}

// TraitID identifies the trait.
func (*ContextParam) TraitID() string { return "smithy.rules#contextParam" }

// AWSQueryError represents aws.protocols#awsQueryError.
type AWSQueryError struct {
	ErrorCode  string
	StatusCode int
}

// TraitID identifies the trait.
func (*AWSQueryError) TraitID() string { return "aws.protocols#awsQueryError" }

// EC2QueryName represents aws.protocols#ec2QueryName.
type EC2QueryName struct {
	Name string
}

// TraitID identifies the trait.
func (*EC2QueryName) TraitID() string { return "aws.protocols#ec2QueryName" }

// UnitShape is a synthetic trait applied to input/output shapes that were
// backfilled from Unit. It indicates the shape has no defined members and
// should be treated as absent for protocol serialization purposes.
type UnitShape struct{}

// TraitID identifies the trait.
func (*UnitShape) TraitID() string { return "smithy.go#unitShape" }
