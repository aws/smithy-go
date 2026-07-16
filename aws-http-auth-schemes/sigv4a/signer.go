package sigv4a

import (
	"context"
	"fmt"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/auth"
	awssigv4a "github.com/aws/smithy-go/aws-http-auth/sigv4a"
	v4 "github.com/aws/smithy-go/aws-http-auth/v4"
	"github.com/aws/smithy-go/aws-http-auth-schemes/identity"
	"github.com/aws/smithy-go/aws-http-auth-schemes/internal/payloadhash"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// signer adapts the standalone [awssigv4a.Signer] to the client-side
// [smithyhttp.Signer] interface, sourcing the signing name and region set from
// the request's resolved auth properties.
type signer struct {
	Signer *awssigv4a.Signer
}

var _ smithyhttp.Signer = (*signer)(nil)

// newSigner returns a signer backed by a default SigV4A signer configured
// with the given options.
func newSigner(opts ...v4.SignerOption) *signer {
	return &signer{Signer: awssigv4a.New(opts...)}
}

// SignRequest implements [smithyhttp.Signer].
func (a *signer) SignRequest(
	ctx context.Context, r *smithyhttp.Request, ident auth.Identity, props smithy.Properties,
) error {
	ci, ok := ident.(*identity.AWSCredentialIdentity)
	if !ok {
		return fmt.Errorf("sigv4a: unexpected identity type %T", ident)
	}

	name, ok := smithyhttp.GetSigV4ASigningName(&props)
	if !ok {
		return fmt.Errorf("sigv4a: signing name is required")
	}

	regions, ok := smithyhttp.GetSigV4ASigningRegions(&props)
	if !ok {
		return fmt.Errorf("sigv4a: signing region set is required")
	}

	hash, err := payloadhash.Hash(r, &props)
	if err != nil {
		return fmt.Errorf("sigv4a: %w", err)
	}

	in := &awssigv4a.SignRequestInput{
		Request:     r.Request,
		Credentials: ci.Credentials,
		Service:     name,
		RegionSet:   regions,
		PayloadHash: hash,
	}

	if err := a.Signer.SignRequest(in); err != nil {
		return fmt.Errorf("sigv4a: sign request: %w", err)
	}

	return nil
}
