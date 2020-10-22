package http

import "context"

type (
	hostnameImmutableKey struct{}
)

// GetHostnameImmutable retrieves if the endpoint hostname should be considered
// immutable or not.
func GetHostnameImmutable(ctx context.Context) (v bool) {
	v, _ = ctx.Value(hostnameImmutableKey{}).(bool)
	return v
}

// SetHostnameImmutable sets or modifies if the request's endpoint hostname
// should be considered immutable or not.
func SetHostnameImmutable(ctx context.Context, value bool) context.Context {
	return context.WithValue(ctx, hostnameImmutableKey{}, value)
}
