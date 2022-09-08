package transport

import "github.com/aws/smithy-go"

// Endpoint is a Smithy endpoint.
type Endpoint struct {
	URI string

	Fields *FieldSet

	Properties smithy.Properties
}
