package http

import (
	"context"

	smithy "github.com/aws/smithy-go"
	"github.com/aws/smithy-go/auth"
)

const (
	// SchemeIDSigV4 identifies the SigV4 auth scheme.
	SchemeIDSigV4 = "aws.auth#sigv4"

	// SchemeIDSigV4A identifies the SigV4A auth scheme.
	SchemeIDSigV4A = "aws.auth#sigv4a"

	// SchemeIDBearer identifies the HTTP Bearer auth scheme.
	SchemeIDBearer = "smithy.api#httpBearerAuth"

	// SchemeIDAnonymous identifies the anonymous or "no-auth" scheme.
	SchemeIDAnonymous = "smithy.api#noAuth"
)

// NewSigV4Scheme returns a SigV4 auth scheme that uses the given Signer.
func NewSigV4Scheme(signer Signer) AuthScheme {
	return &authScheme{
		schemeID: SchemeIDSigV4,
		signer:   signer,
	}
}

// NewSigV4AScheme returns a SigV4A auth scheme that uses the given Signer.
func NewSigV4AScheme(signer Signer) AuthScheme {
	return &authScheme{
		schemeID: SchemeIDSigV4A,
		signer:   signer,
	}
}

// NewBearerScheme returns an HTTP bearer auth scheme that uses the given Signer.
func NewBearerScheme(signer Signer) AuthScheme {
	return &authScheme{
		schemeID: SchemeIDBearer,
		signer:   signer,
	}
}

// NewBearerScheme returns an anonymous auth scheme.
func NewAnonymousScheme() AuthScheme {
	return &authScheme{
		schemeID: SchemeIDAnonymous,
		signer:   &nopSigner{},
	}
}

// authScheme is parameterized to generically implement the exported AuthScheme
// interface
type authScheme struct {
	schemeID string
	signer   Signer
}

var _ AuthScheme = (*authScheme)(nil)

func (s *authScheme) SchemeID() string {
	return s.schemeID
}

func (s *authScheme) IdentityResolver(o auth.IdentityResolverOptions) auth.IdentityResolver {
	return o.GetIdentityResolver(s.schemeID)
}

func (s *authScheme) Signer() Signer {
	return s.signer
}

type nopSigner struct{}

var _ Signer = (*nopSigner)(nil)

func (*nopSigner) SignRequest(context.Context, *Request, auth.Identity, smithy.Properties) error {
	return nil
}
