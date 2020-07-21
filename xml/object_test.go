package xml

import (
	"bytes"
	"testing"
)

func TestObject(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	object := newObject(buffer, &scratch, nil)
	object.Key("foo", nil).String("bar")
	object.Key("faz", nil).String("baz")

	e := []byte(`<foo>bar</foo><faz>baz</faz>`)
	if a := buffer.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}
}

func TestObjectWithNameSpaceAndAttributes(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	object := newObject(buffer, &scratch, nil)

	ns := Attr{
		Name: Name{
			Space: "xmlns",
			Local: "newspace",
		},
		Value: "https://endpoint.com",
	}

	attr := Attr{
		Name: Name{
			Local: "attrName",
		},
		Value: "attrValue",
	}

	attributes := []Attr{ns, attr}
	object.Key("foo", &attributes).String("bar")
	object.Key("faz", nil).String("baz")

	e := []byte(`<foo xmlns:newspace="https://endpoint.com" attrName="attrValue">bar</foo><faz>baz</faz>`)
	if a := buffer.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}
}
