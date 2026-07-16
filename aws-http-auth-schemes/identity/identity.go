// Package identity provides the AWS credential identity types shared by the
// generic smithy-go AWS auth schemes (SigV4, SigV4A).
package identity

import (
	"context"
	"time"

	smithy "github.com/aws/smithy-go"
	"github.com/aws/smithy-go/auth"
	"github.com/aws/smithy-go/aws-http-auth/credentials"
)

// AWSCredentialIdentity is the [auth.Identity] carrying AWS credentials
// through the auth pipeline.
type AWSCredentialIdentity struct {
	credentials.Credentials
}

var _ auth.Identity = (*AWSCredentialIdentity)(nil)

// Expiration returns when the underlying credentials expire.
func (i *AWSCredentialIdentity) Expiration() time.Time {
	return i.Expires
}

// AWSCredentialIdentityResolver resolves an [AWSCredentialIdentity].
//
// This is the concrete, credential-flavored counterpart to [auth.IdentityResolver]
// for the SigV4/SigV4A auth schemes. Implementations resolve AWS credentials
// (static, environment, assumed-role, etc.) and return them boxed as an
// [AWSCredentialIdentity].
type AWSCredentialIdentityResolver interface {
	GetIdentity(context.Context, smithy.Properties) (*AWSCredentialIdentity, error)
}

// staticAWSCredentialIdentityResolver resolves a fixed, unchanging
// [AWSCredentialIdentity].
type staticAWSCredentialIdentityResolver struct {
	identity AWSCredentialIdentity
}

var _ AWSCredentialIdentityResolver = (*staticAWSCredentialIdentityResolver)(nil)

// GetIdentity implements [AWSCredentialIdentityResolver].
func (r *staticAWSCredentialIdentityResolver) GetIdentity(context.Context, smithy.Properties) (*AWSCredentialIdentity, error) {
	return &r.identity, nil
}

// NewStaticAWSCredentialIdentityResolver returns an [AWSCredentialIdentityResolver]
// that always resolves to the given, unchanging AWS credentials.
func NewStaticAWSCredentialIdentityResolver(accessKeyID, secretAccessKey, sessionToken string) AWSCredentialIdentityResolver {
	return &staticAWSCredentialIdentityResolver{
		identity: AWSCredentialIdentity{
			Credentials: credentials.Credentials{
				AccessKeyID:     accessKeyID,
				SecretAccessKey: secretAccessKey,
				SessionToken:    sessionToken,
			},
		},
	}
}

// identityResolverAdapter adapts an [AWSCredentialIdentityResolver] to the
// pipeline's opaque [auth.IdentityResolver] interface.
type identityResolverAdapter struct {
	Resolver AWSCredentialIdentityResolver
}

var _ auth.IdentityResolver = (*identityResolverAdapter)(nil)

// GetIdentity implements [auth.IdentityResolver].
func (a *identityResolverAdapter) GetIdentity(ctx context.Context, props smithy.Properties) (auth.Identity, error) {
	return a.Resolver.GetIdentity(ctx, props)
}

// NewIdentityResolver adapts an [AWSCredentialIdentityResolver] to the
// pipeline's opaque [auth.IdentityResolver] interface.
func NewIdentityResolver(resolver AWSCredentialIdentityResolver) auth.IdentityResolver {
	return &identityResolverAdapter{Resolver: resolver}
}
