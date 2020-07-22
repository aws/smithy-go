package xml

import (
	"bytes"
	"testing"
)

func TestWrappedArray(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	root := StartElement{Name: Name{Local: "array"}}
	a := newArray(buffer, &scratch, arrayMemberWrapper, root)
	a.Member().String("bar")
	a.Member().String("baz")
	a.Close()

	e := []byte(`<array><member>bar</member><member>baz</member></array>`)
	if a := buffer.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}
}

func TestWrappedArrayWithCustomName(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	root := StartElement{Name: Name{Local: "array"}}
	item := StartElement{Name: Name{Local: "item"}}
	a := newArray(buffer, &scratch, item, root)
	a.Member().String("bar")
	a.Member().String("baz")
	a.Close()

	e := []byte(`<array><item>bar</item><item>baz</item></array>`)
	if a := buffer.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}
}

func TestFlattenedArray(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	root := StartElement{Name: Name{Local: "array"}}
	a := newFlattenedArray(buffer, &scratch, root)
	a.Member().String("bar")
	a.Member().String("bix")
	a.Close()

	e := []byte(`<array>bar</array><array>bix</array>`)
	if a := buffer.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}
}
