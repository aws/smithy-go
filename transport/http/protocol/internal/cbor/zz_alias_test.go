package cbor

import (
	"bytes"
	"testing"

	"github.com/aws/smithy-go"
)

// The payload handed to NewShapeDeserializer aliases a pooled buffer that the
// protocol returns to its pool as soon as deserialization finishes. Anything the
// deserializer yields to the caller must therefore be copied out of it. These
// tests prove that by scribbling over the payload afterward: if a value aliased
// it, the value changes.

func scribble(p []byte) {
	for i := range p {
		p[i] = 0xAA
	}
}

func TestBlobIsCopiedOutOfPayload(t *testing.T) {
	// {"b": h'DEADBEEF'} -- map(1), text(1) "b", bytes(4)
	payload := []byte{
		0xa1,
		0x61, 'b',
		0x44, 0xDE, 0xAD, 0xBE, 0xEF,
	}
	want := []byte{0xDE, 0xAD, 0xBE, 0xEF}

	schema := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "S"}, smithy.ShapeTypeStructure, 1)
	blobTarget := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "B"}, smithy.ShapeTypeBlob, 0)
	schema.AddMember("b", blobTarget)

	d := NewShapeDeserializer(payload)
	if err := d.ReadStruct(schema); err != nil {
		t.Fatal(err)
	}
	member, err := d.ReadStructMember()
	if err != nil {
		t.Fatal(err)
	}
	if member == nil {
		t.Fatal("no member read")
	}

	var got []byte
	if err := d.ReadBlob(member, &got); err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, want) {
		t.Fatalf("got %x, want %x", got, want)
	}

	scribble(payload)

	if !bytes.Equal(got, want) {
		t.Fatalf("blob ALIASES the payload: became %x after the buffer was reused, want %x", got, want)
	}
}

func TestStringAndMapKeyAreCopiedOutOfPayload(t *testing.T) {
	// {"key": "value"}
	payload := []byte{
		0xa1,
		0x63, 'k', 'e', 'y',
		0x65, 'v', 'a', 'l', 'u', 'e',
	}

	mapSchema := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "M"}, smithy.ShapeTypeMap, 0)

	d := NewShapeDeserializer(payload)
	if err := d.ReadMap(mapSchema); err != nil {
		t.Fatal(err)
	}
	key, ok, err := d.ReadMapKey(mapSchema)
	if err != nil || !ok {
		t.Fatalf("ReadMapKey: %v ok=%v", err, ok)
	}
	var val string
	if err := d.ReadString(nil, &val); err != nil {
		t.Fatal(err)
	}
	if key != "key" || val != "value" {
		t.Fatalf("got key=%q val=%q", key, val)
	}

	scribble(payload)

	if key != "key" {
		t.Errorf("map key ALIASES the payload: became %q", key)
	}
	if val != "value" {
		t.Errorf("string value ALIASES the payload: became %q", val)
	}
}

// An indefinite-length byte string is legal CBOR that ReadBlob does not
// implement. It must fail cleanly rather than mis-parse into payload memory.
func TestIndefiniteLengthBlobRejected(t *testing.T) {
	// h'' with indefinite length: 0x5f <chunks> 0xff
	payload := []byte{0x5f, 0x42, 0xDE, 0xAD, 0xff}

	blobSchema := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "B"}, smithy.ShapeTypeBlob, 0)

	d := NewShapeDeserializer(payload)
	var got []byte
	err := d.ReadBlob(blobSchema, &got)
	if err == nil {
		t.Fatalf("expected an error for an indefinite-length blob, got %x", got)
	}
	t.Logf("indefinite-length blob rejected with: %v", err)
}
