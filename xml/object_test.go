package xml

import (
	"bytes"
	"testing"
)

func TestObject(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	object := newObject(buffer, &scratch)
	object.Key("foo").String("bar")
	object.Key("faz").String("baz")

	e := []byte(`<foo>bar</foo><faz>baz</faz>`)
	if a := buffer.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}
}

func TestObjectWithNameSpaceAndAttributes(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	object := newObject(buffer, &scratch)

	var metadata = func(t *TagMetadata) {
		t.NamespacePrefix = "newspace"
		t.NamespaceURI = "https://endpoint.com"
		t.AttributeName = "attrName"
		t.AttributeValue = "attrValue"
	}

	object.Key("foo", metadata).String("bar")
	object.Key("faz").String("baz")

	e := []byte(`<foo xmlns:newspace="https://endpoint.com" attrName="attrValue">bar</foo><faz>baz</faz>`)
	if a := buffer.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}
}
