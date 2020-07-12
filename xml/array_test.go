package xml

import (
	"bytes"
	"testing"
)

func TestWrappedArray(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	array := newArray(buffer, &scratch)
	array.Value().String("bar")
	array.Value().String("baz")

	e := []byte(`<member>bar</member><member>baz</member>`)
	if a := buffer.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}
}

func TestWrappedArrayWithCustomName(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	array := newArrayWithKey(buffer, &scratch, "item")
	array.Value().String("bar")
	array.Value().String("baz")

	e := []byte(`<item>bar</item><item>baz</item>`)
	if a := buffer.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}
}

func TestFlattenedArray(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	array := newArrayWithKey(buffer, &scratch, "flattened")

	array.Value().String("bar")
	array.Value().String("bix")

	e := []byte(`<flattened>bar</flattened><flattened>bix</flattened>`)
	if a := buffer.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}
}
