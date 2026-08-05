package serde

import (
	"bytes"
	"testing"
)

func TestReadPayloadBlobHugeContentLength(t *testing.T) {
	body := []byte("short")

	buf, err := ReadPayloadBlob(bytes.NewReader(body), 64<<30)
	if err != nil {
		t.Fatal(err)
	}

	if !bytes.Equal(buf.Bytes(), body) {
		t.Fatalf("got %q, want %q", buf.Bytes(), body)
	}

	if limit := int64(maxPresize) + bytes.MinRead + 64<<10; int64(buf.Cap()) > limit {
		t.Errorf("capacity %d exceeds the presize cap %d", buf.Cap(), maxPresize)
	}
}

func TestReadPayloadBlobWrongContentLength(t *testing.T) {
	body := make([]byte, 8192)
	for i := range body {
		body[i] = byte(i)
	}

	buf, err := ReadPayloadBlob(bytes.NewReader(body), 16)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(buf.Bytes(), body) {
		t.Fatal("body truncated when Content-Length understated it")
	}
}
