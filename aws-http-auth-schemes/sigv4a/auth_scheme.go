package sigv4a

import (
	"github.com/aws/smithy-go/auth"
	v4 "github.com/aws/smithy-go/aws-http-auth/v4"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// AuthScheme implements request signing for aws.auth#sigv4a.
type AuthScheme struct {
	signer *signer
}

var _ smithyhttp.AuthScheme = (*AuthScheme)(nil)

// NewAuthScheme returns a SigV4A [AuthScheme] backed by a default signer
// configured with the given options.
func NewAuthScheme(opts ...v4.SignerOption) *AuthScheme {
	return &AuthScheme{signer: newSigner(opts...)}
}

// SchemeID implements [smithyhttp.AuthScheme].
func (s *AuthScheme) SchemeID() string {
	return auth.SchemeIDSigV4A
}

// IdentityResolver implements [smithyhttp.AuthScheme].
func (s *AuthScheme) IdentityResolver(o auth.IdentityResolverOptions) auth.IdentityResolver {
	return o.GetIdentityResolver(s.SchemeID())
}

// Signer implements [smithyhttp.AuthScheme].
func (s *AuthScheme) Signer() smithyhttp.Signer {
	return s.signer
}
