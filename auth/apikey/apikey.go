package apikey

import (
	"context"
)

// An HTTP-specific authentication scheme that sends an arbitrary
// auth value in a header or query string parameter.
// As described in the Smithy documentation:
// https://github.com/awslabs/smithy/blob/main/smithy-model/src/main/resources/software/amazon/smithy/model/loader/prelude.smithy
type HttpApiKeyAuthDefinition struct {
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

// ApiKeyProvider provides interface for retrieving api keys.
type ApiKeyProvider interface {
	RetrieveApiKey(context.Context) (string, error)
}

// ApiKeyProviderFunc provides a helper utility to wrap a function as a type
// that implements the ApiKeyProvider interface.
type ApiKeyProviderFunc func(context.Context) (string, error)

// RetrieveApiKey calls the wrapped function, returning the ApiKey or
// error.
func (fn ApiKeyProviderFunc) RetrieveApiKey(ctx context.Context) (string, error) {
	return fn(ctx)
}

// StaticApiKeyProvider provides a utility for wrapping a static api key
// value within an implementation of an api key provider.
type StaticApiKeyProvider struct {
	ApiKey string
}

// RetrieveApiKey returns the static api key specified.
func (s StaticApiKeyProvider) RetrieveApiKey(context.Context) (string, error) {
	return s.ApiKey, nil
}
