package xml

import (
	"bytes"
	"testing"
)

func TestWrappedMap(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	m := newMap(buffer, &scratch, nil)

	// map entry
	e := m.Entry()
	e.Key("key", nil).String("example-key1")
	e.Key("value", nil).String("example1")
	e.Close()

	// map entry
	e = m.Entry()
	e.Key("key", nil).String("example-key2")
	e.Key("value", nil).String("example2")
	e.Close()

	// map entry
	e = m.Entry()
	e.Key("key", nil).String("example-key3")
	e.Key("value", nil).String("example3")
	e.Close()

	ex := []byte(`<entry><key>example-key1</key><value>example1</value></entry><entry><key>example-key2</key><value>example2</value></entry><entry><key>example-key3</key><value>example3</value></entry>`)
	if a := buffer.Bytes(); bytes.Compare(ex, a) != 0 {
		t.Errorf("expected %+q, but got %+q", ex, a)
	}
}

func TestFlattenedMapWithCustomName(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	startElement := StartElement{
		Name: Name{
			Local: "flatMap",
		},
	}

	endElement := startElement.End()

	m := newFlattenedMap(buffer, &scratch, &startElement, &endElement)

	// map entry
	e := m.Entry()
	e.Key("key", nil).String("example-key1")
	e.Key("value", nil).String("example1")
	e.Close()

	// map entry
	e = m.Entry()
	e.Key("key", nil).String("example-key2")
	e.Key("value", nil).String("example2")
	e.Close()

	// map entry
	e = m.Entry()
	e.Key("key", nil).String("example-key3")
	e.Key("value", nil).String("example3")
	e.Close()

	ex := []byte(`<flatMap><key>example-key1</key><value>example1</value></flatMap><flatMap><key>example-key2</key><value>example2</value></flatMap><flatMap><key>example-key3</key><value>example3</value></flatMap>`)
	if a := buffer.Bytes(); bytes.Compare(ex, a) != 0 {
		t.Errorf("expected %+q, but got %+q", ex, a)
	}
}
