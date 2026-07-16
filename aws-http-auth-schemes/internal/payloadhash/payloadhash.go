// Package payloadhash provides the shared payload hashing logic used by the
// generic smithy-go AWS auth scheme signers (SigV4, SigV4A).
package payloadhash

import (
	"crypto/sha256"
	"fmt"
	"io"

	smithy "github.com/aws/smithy-go"
	v4 "github.com/aws/smithy-go/aws-http-auth/v4"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// Hash returns the SHA256 hash of the request payload, or the SigV4/SigV4A
// unsigned-payload sentinel if the payload is not signed.
//
// The aws-http-auth signers support implicit payload hashing, but in the
// client pipeline the body is carried on the separate Stream field, so it has
// to be hashed here instead.
func Hash(r *smithyhttp.Request, props *smithy.Properties) ([]byte, error) {
	if unsigned, _ := smithyhttp.GetIsUnsignedPayload(props); unsigned {
		return v4.UnsignedPayload(), nil
	}

	stream := r.GetStream()
	if stream == nil {
		sum := sha256.Sum256(nil)
		return sum[:], nil
	}

	if !r.IsStreamSeekable() {
		return v4.UnsignedPayload(), nil
	}

	h := sha256.New()
	if _, err := io.Copy(h, stream); err != nil {
		return nil, fmt.Errorf("hash payload: %w", err)
	}
	if err := r.RewindStream(); err != nil {
		return nil, fmt.Errorf("rewind payload: %w", err)
	}

	return h.Sum(nil), nil
}
