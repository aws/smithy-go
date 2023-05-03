// The '@httpApiKeyAuth' trait support is experimental and subject to breaking changes.
package auth

type key string

const (
	// The current auth configuration that has been set by any auth middleware and
	// that will prevent from being set more than once.
	CURRENT_AUTH_CONFIG key = "currentAuthConfig"
)
