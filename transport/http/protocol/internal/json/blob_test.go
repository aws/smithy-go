package json

import (
	"bytes"
	"encoding/base64"
	stdjson "encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"
)

var blobTests = []struct {
	name, input string
}{
	{"null", `null`},
	{"empty", `""`},
	{"one byte", `"Zg=="`},
	{"two bytes", `"Zm8="`},
	{"three bytes", `"Zm9v"`},
	{"escaped alphabet", `"\u005am9v"`},
	{"escaped padding", `"Zg\u003d\u003d"`},
	{"escaped slash", `"\/w=="`},
	{"escaped newline", `"Zm9v\r\nYmFy"`},
	{"invalid alphabet", `"Zm$v"`},
	{"invalid padding", `"Zg=a"`},
	{"truncated", `"Zm9"`},
	{"non-ASCII", `"Zm9vé"`},
	{"invalid UTF-8", "\"Zm9v\xff\""},
	{"surrogate", `"Zm9v\ud800"`},
}

func TestReadBlob(t *testing.T) {
	for _, tc := range blobTests {
		t.Run(tc.name, func(t *testing.T) {
			checkReadBlob(t, []byte(tc.input))
		})
	}
}

// Compare decoded bytes and corrupt-input offsets with the standard JSON decoder.
func checkReadBlob(t *testing.T, input []byte) {
	t.Helper()
	var want []byte
	wantErr := stdjson.Unmarshal(input, &want)
	var wantCorrupt base64.CorruptInputError
	if wantErr != nil && !errors.As(wantErr, &wantCorrupt) {
		t.Fatalf("invalid test input %q: %v", input, wantErr)
	}

	d := NewShapeDeserializer(input)
	defer d.Close()
	var got []byte
	if wantErr != nil {
		got = []byte("unchanged")
	}
	gotErr := d.ReadBlob(nil, &got)
	if wantErr != nil {
		var gotCorrupt base64.CorruptInputError
		if !errors.As(gotErr, &gotCorrupt) || gotCorrupt != wantCorrupt {
			t.Fatalf("input %q: got error %v, want corrupt input at %d", input, gotErr, wantCorrupt)
		}
		if gotErr.Error() != "decode base64 blob: "+wantErr.Error() {
			t.Errorf("unexpected error context: %v", gotErr)
		}
		if string(got) != "unchanged" {
			t.Errorf("failed decode changed destination to %q", got)
		}
		return
	}
	if gotErr != nil {
		t.Fatalf("input %q: unexpected error: %v", input, gotErr)
	}
	if !bytes.Equal(got, want) || (got == nil) != (want == nil) {
		t.Errorf("input %q: got %#v, want %#v", input, got, want)
	}
}

func TestReadBlobOwnsData(t *testing.T) {
	for _, input := range []string{`"Zm9v"`, `"\u005am9v"`} {
		payload := []byte(input)
		d := NewShapeDeserializer(payload)
		var got []byte
		if err := d.ReadBlob(nil, &got); err != nil {
			t.Fatal(err)
		}
		d.Close()
		clear(payload)
		if string(got) != "foo" {
			t.Fatalf("modifying input changed decoded blob to %q", got)
		}
	}
}

func FuzzReadBlob(f *testing.F) {
	for _, tc := range blobTests {
		f.Add([]byte(tc.input))
	}
	f.Fuzz(func(t *testing.T, input []byte) {
		trimmed := bytes.TrimSpace(input)
		if !stdjson.Valid(input) || len(trimmed) == 0 || (trimmed[0] != '"' && string(trimmed) != "null") {
			return
		}
		checkReadBlob(t, input)
	})
}

func BenchmarkReadBlob(b *testing.B) {
	for _, size := range []int{64, 4096, 65536} {
		data := bytes.Repeat([]byte{0xff}, size)
		encoded := base64.StdEncoding.EncodeToString(data)
		for _, escaped := range []bool{false, true} {
			b.Run(fmt.Sprintf("bytes=%d/escaped=%t", size, escaped), func(b *testing.B) {
				text := encoded
				if escaped {
					text = strings.ReplaceAll(text, "/", `\/`)
				}
				input := []byte(`"` + text + `"`)
				b.SetBytes(int64(len(data)))
				b.ReportAllocs()
				for b.Loop() {
					d := NewShapeDeserializer(input)
					var got []byte
					err := d.ReadBlob(nil, &got)
					d.Close()
					if err != nil {
						b.Fatal(err)
					}
				}
			})
		}
	}
}
