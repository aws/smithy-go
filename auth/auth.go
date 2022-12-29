package auth

type key string

const (
	// The current auth configuration that has been set by any auth middleware and
	// that will prevent from being set more than once.
	CURRENT_AUTH_CONFIG key = "currentAuthConfig"
)

// An HTTP-specific authentication scheme that sends an arbitrary
// auth value in a header or query string parameter.
// As described in the Smithy documentation:
// https://github.com/awslabs/smithy/blob/main/smithy-model/src/main/resources/software/amazon/smithy/model/loader/prelude.smithy
type HttpAuthDefinition struct {
	// Defines the location of where the Auth is serialized. This value
	// can be set to `"header"` or `"query"`.
	In string

	// Defines the name of the HTTP header or query string parameter
	// that contains the Auth.
	Name string

	// Defines the security scheme to use on the `Authorization` header value.
	// This can only be set if the "in" property is set to `"header"`.
	Scheme string
}
