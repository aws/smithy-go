package sigv4

import (
	"context"
	"fmt"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/auth"
	awssigv4 "github.com/aws/smithy-go/aws-http-auth/sigv4"
	v4 "github.com/aws/smithy-go/aws-http-auth/v4"
	"github.com/aws/smithy-go/aws-http-auth-schemes/identity"
	"github.com/aws/smithy-go/aws-http-auth-schemes/internal/payloadhash"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// signer adapts the standalone [awssigv4.Signer] to the client-side
// [smithyhttp.Signer] interface, sourcing the signing name and region from the
// request's resolved auth properties.
type signer struct {
	Signer *awssigv4.Signer
}

var _ smithyhttp.Signer = (*signer)(nil)

// newSigner returns a signer backed by a default SigV4 signer configured
// with the given options.
func newSigner(opts ...v4.SignerOption) *signer {
	return &signer{Signer: awssigv4.New(opts...)}
}

// SignRequest implements [smithyhttp.Signer].
func (a *signer) SignRequest(
	ctx context.Context, r *smithyhttp.Request, ident auth.Identity, props smithy.Properties,
) error {
	ci, ok := ident.(*identity.AWSCredentialIdentity)
	if !ok {
		return fmt.Errorf("sigv4: unexpected identity type %T", ident)
	}

	name, ok := smithyhttp.GetSigV4SigningName(&props)
	if !ok {
		return fmt.Errorf("sigv4: signing name is required")
	}

	region, ok := smithyhttp.GetSigV4SigningRegion(&props)
	if !ok {
		return fmt.Errorf("sigv4: signing region is required")
	}

	hash, err := payloadhash.Hash(r, &props)
	if err != nil {
		return fmt.Errorf("sigv4: %w", err)
	}

	in := &awssigv4.SignRequestInput{
		Request:     r.Request,
		Credentials: ci.Credentials,
		Service:     name,
		Region:      region,
		PayloadHash: hash,
	}

	if err := a.Signer.SignRequest(in); err != nil {
		return fmt.Errorf("sigv4: sign request: %w", err)
	}

	return nil
}
