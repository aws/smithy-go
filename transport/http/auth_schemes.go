package http

import (
	"github.com/aws/smithy-go/auth"
)

// NewSigV4Scheme returns a SigV4 auth scheme that uses the given Signer.
func NewSigV4Scheme(signer Signer) AuthScheme {
	return &authScheme{
		schemeID: "aws.auth#sigv4",
		signer:   signer,
	}
}

// NewSigV4AScheme returns a SigV4A auth scheme that uses the given Signer.
func NewSigV4AScheme(signer Signer) AuthScheme {
	return &authScheme{
		schemeID: "aws.auth#sigv4a",
		signer:   signer,
	}
}

// NewBearerScheme returns an HTTP bearer auth scheme that uses the given Signer.
func NewBearerScheme(signer Signer) AuthScheme {
	return &authScheme{
		schemeID: "aws.auth#httpBearerAuth",
		signer:   signer,
	}
}

// authScheme is parameterized to generically implement the exported AuthScheme
// interface
type authScheme struct {
	schemeID string
	signer   Signer
}

var _ (AuthScheme) = (*authScheme)(nil)

func (s *authScheme) SchemeID() string {
	return s.schemeID
}

func (s *authScheme) IdentityResolver(o auth.IdentityResolverOptions) auth.IdentityResolver {
	return o.GetIdentityResolver(s.schemeID)
}

func (s *authScheme) Signer() Signer {
	return s.signer
}
