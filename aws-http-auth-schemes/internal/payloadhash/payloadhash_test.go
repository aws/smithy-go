package payloadhash

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"testing"

	"github.com/aws/smithy-go"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// emptyStringSHA256 is the well-known SHA256 of an empty payload that services compute for
// bodyless requests (e.g. GET). Sending UNSIGNED-PAYLOAD instead causes InvalidSignatureException.
const emptyStringSHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

func TestHashEmptyBody(t *testing.T) {
	r := smithyhttp.NewStackRequest().(*smithyhttp.Request)

	got, err := Hash(r, &smithy.Properties{})
	if err != nil {
		t.Fatalf("Hash: %v", err)
	}
	if hex.EncodeToString(got) != emptyStringSHA256 {
		t.Fatalf("empty-body hash = %s, want %s", hex.EncodeToString(got), emptyStringSHA256)
	}
}

func TestHashSeekableBody(t *testing.T) {
	body := []byte(`{"name":"foo"}`)
	r := smithyhttp.NewStackRequest().(*smithyhttp.Request)
	r, err := r.SetStream(bytes.NewReader(body))
	if err != nil {
		t.Fatalf("SetStream: %v", err)
	}

	got, err := Hash(r, &smithy.Properties{})
	if err != nil {
		t.Fatalf("Hash: %v", err)
	}
	want := sha256.Sum256(body)
	if !bytes.Equal(got, want[:]) {
		t.Fatalf("body hash = %x, want %x", got, want)
	}

	// stream must be rewound so the body can still be sent
	rest, err := io.ReadAll(r.GetStream())
	if err != nil {
		t.Fatalf("read stream after hashing: %v", err)
	}
	if !bytes.Equal(rest, body) {
		t.Fatalf("stream not rewound: got %q, want %q", rest, body)
	}
}

func TestHashUnsignedOverride(t *testing.T) {
	r := smithyhttp.NewStackRequest().(*smithyhttp.Request)
	var props smithy.Properties
	smithyhttp.SetIsUnsignedPayload(&props, true)

	got, err := Hash(r, &props)
	if err != nil {
		t.Fatalf("Hash: %v", err)
	}
	if string(got) != "UNSIGNED-PAYLOAD" {
		t.Fatalf("unsigned override = %q, want UNSIGNED-PAYLOAD", got)
	}
}
