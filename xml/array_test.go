package xml

import (
	"bytes"
	"testing"
)

func TestWrappedArray(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	a := newArray(buffer, &scratch)
	a.NewMember().String("bar")
	a.NewMember().String("baz")

	e := []byte(`<member>bar</member><member>baz</member>`)
	if a := buffer.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}
}

func TestWrappedArrayWithCustomName(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	a := newArrayWithCustomName(buffer, &scratch, "item")
	a.NewMember().String("bar")
	a.NewMember().String("baz")

	e := []byte(`<item>bar</item><item>baz</item>`)
	if a := buffer.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}
}

func TestFlattenedArray(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	a := newFlattenedArray(buffer, &scratch, func() {
		buffer.Write([]byte("<flattened>"))
	}, func() {
		buffer.Write([]byte("</flattened>"))
	})

	a.NewMember().String("bar")
	a.NewMember().String("bix")

	e := []byte(`<flattened>bar</flattened><flattened>bix</flattened>`)
	if a := buffer.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}
}
