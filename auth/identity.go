package auth

import (
	"context"
	"time"

	"github.com/aws/smithy-go"
)

// Identity contains information that identifies who the user making the
// request is.
type Identity interface {
	Expiration() time.Time
}

// IdentityResolver defines the interface through which an Identity is
// retrieved.
type IdentityResolver interface {
	GetIdentity(context.Context, smithy.Properties) (Identity, error)
}

// IdentityResolverOptions defines the interface through which an entity can be
// queried to retrieve an IdentityResolver for a given auth scheme.
type IdentityResolverOptions interface {
	GetIdentityResolver(schemeID string) IdentityResolver
}
