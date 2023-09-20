package auth

import "github.com/aws/smithy-go"

// Option represents a possible authentication method for an operation.
type Option struct {
	SchemeID           string
	IdentityProperties smithy.Properties
	SignerProperties   smithy.Properties
}
