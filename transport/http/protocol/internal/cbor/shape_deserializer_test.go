package cbor

import (
	"encoding/binary"
	"math"
	"testing"

	"github.com/aws/smithy-go"
)

func oversizedLengthToken(major majorType) []byte {
	p := make([]byte, 9)
	p[0] = byte(major)<<5 | minorArg8
	binary.BigEndian.PutUint64(p[1:], uint64(math.MaxInt64)+1)
	return p
}

func TestOversizeLength(t *testing.T) {
	blobSchema := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "B"}, smithy.ShapeTypeBlob, 0)

	tests := []struct {
		name    string
		payload []byte
		read    func(*ShapeDeserializer) error
	}{
		{
			name:    "string",
			payload: oversizedLengthToken(majorTypeString),
			read: func(d *ShapeDeserializer) error {
				var value string
				return d.ReadString(nil, &value)
			},
		},
		{
			name:    "indefinite string chunk",
			payload: append(append([]byte{0x7f}, oversizedLengthToken(majorTypeString)...), 0xff),
			read: func(d *ShapeDeserializer) error {
				var value string
				return d.ReadString(nil, &value)
			},
		},
		{
			name:    "blob",
			payload: oversizedLengthToken(majorTypeSlice),
			read: func(d *ShapeDeserializer) error {
				var value []byte
				return d.ReadBlob(blobSchema, &value)
			},
		},
		{
			name:    "skip",
			payload: oversizedLengthToken(majorTypeString),
			read:    func(d *ShapeDeserializer) error { return d.skip() },
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if err := tt.read(NewShapeDeserializer(tt.payload)); err == nil {
				t.Fatal("expected an error")
			}
		})
	}
}

func TestInBoundsLengths(t *testing.T) {
	t.Run("string", func(t *testing.T) {
		d := NewShapeDeserializer([]byte{0x63, 'f', 'o', 'o'})
		var value string
		if err := d.ReadString(nil, &value); err != nil {
			t.Fatal(err)
		}
		if value != "foo" {
			t.Fatalf("got %q, want %q", value, "foo")
		}
	})

	t.Run("indefinite string", func(t *testing.T) {
		d := NewShapeDeserializer([]byte{0x7f, 0x62, 'f', 'o', 0x61, 'o', 0xff})
		var value string
		if err := d.ReadString(nil, &value); err != nil {
			t.Fatal(err)
		}
		if value != "foo" {
			t.Fatalf("got %q, want %q", value, "foo")
		}
	})

	t.Run("blob", func(t *testing.T) {
		d := NewShapeDeserializer([]byte{0x43, 1, 2, 3})
		var value []byte
		if err := d.ReadBlob(nil, &value); err != nil {
			t.Fatal(err)
		}
		if got, want := string(value), string([]byte{1, 2, 3}); got != want {
			t.Fatalf("got %x, want %x", value, []byte{1, 2, 3})
		}
	})

	t.Run("skip", func(t *testing.T) {
		d := NewShapeDeserializer([]byte{0x43, 1, 2, 3, 0x01})
		if err := d.skip(); err != nil {
			t.Fatal(err)
		}
		value, err := d.readInt64()
		if err != nil {
			t.Fatal(err)
		}
		if value != 1 {
			t.Fatalf("got %d, want 1", value)
		}
	})
}
