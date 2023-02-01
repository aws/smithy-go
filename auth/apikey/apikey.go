package apikey

import (
	"context"
)

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
