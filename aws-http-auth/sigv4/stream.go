package sigv4

import (
	"crypto/sha256"
	"encoding/hex"
	"hash"
	"strings"
	"time"

	"github.com/aws/smithy-go/aws-http-auth/credentials"
	v4internal "github.com/aws/smithy-go/aws-http-auth/internal/v4"
)

// EventStreamSigner implements SigV4 event stream message signing.
//
// Unlike request signing, event stream message signing is **stateful**. The
// signer must be seeded with an initial signature from the originating HTTP
// request, and each message is signed using the signature of the previous
// message (starting at that seed value). Therefore the caller **MUST NOT**
// reuse an instance of an EventStreamSigner across multiple streams.
type EventStreamSigner struct {
	creds   credentials.Credentials
	service string
	region  string
	prev    []byte
}

// EventStreamSignerOptions is reserved for future use.
type EventStreamSignerOptions struct{}

// NewEventStreamSigner returns an EventStreamSigner for a single stream.
func NewEventStreamSigner(creds credentials.Credentials, service, region string, seed []byte,
	opts ...func(*EventStreamSignerOptions)) *EventStreamSigner {
	return &EventStreamSigner{
		creds:   creds,
		service: service,
		region:  region,
		prev:    seed,
	}
}

// SignMessage computes the signature for the next message in the event stream.
func (s *EventStreamSigner) SignMessage(headers, payload []byte, now time.Time) ([]byte, error) {
	now = now.UTC()

	scopeNow := now.Format(v4internal.ShortTimeFormat)
	key := deriveKey(s.creds.SecretAccessKey, s.service, s.region, scopeNow)
	scope := strings.Join([]string{
		scopeNow,
		s.region,
		s.service,
		"aws4_request",
	}, "/")

	h := sha256.New()
	toSign := strings.Join([]string{
		"AWS4-HMAC-SHA256-PAYLOAD",
		now.Format(v4internal.TimeFormat),
		scope,
		hex.EncodeToString(s.prev),
		hex.EncodeToString(mkhash(h, headers)),
		hex.EncodeToString(mkhash(h, payload)),
	}, "\n")

	signature := hmacSHA256(key, []byte(toSign))
	s.prev = signature

	return signature, nil
}

func deriveKey(secret, service, region, shortDate string) []byte {
	key := hmacSHA256([]byte("AWS4"+secret), []byte(shortDate))
	key = hmacSHA256(key, []byte(region))
	key = hmacSHA256(key, []byte(service))
	return hmacSHA256(key, []byte("aws4_request"))
}

func mkhash(h hash.Hash, b []byte) []byte {
	h.Reset()
	h.Write(b)
	return h.Sum(nil)
}
