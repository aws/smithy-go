package xml

import (
	"bytes"
	"testing"
)

func TestWrappedMap(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	m := newMap(buffer, &scratch)

	// map entry
	e, closeFunc1 := m.Entry()
	e.Key("key").String("example-key1")
	e.Key("value").String("example1")
	closeFunc1()

	// map entry
	e, closeFunc2 := m.Entry()
	e.Key("key").String("example-key2")
	e.Key("value").String("example2")
	closeFunc2()

	// map entry
	e, closeFunc3 := m.Entry()
	e.Key("key").String("example-key3")
	e.Key("value").String("example3")
	closeFunc3()

	ex := []byte(`<entry><key>example-key1</key><value>example1</value></entry><entry><key>example-key2</key><value>example2</value></entry><entry><key>example-key3</key><value>example3</value></entry>`)
	if a := buffer.Bytes(); bytes.Compare(ex, a) != 0 {
		t.Errorf("expected %+q, but got %+q", ex, a)
	}
}

func TestFlattenedMapWithCustomName(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	m := newFlattenedMap(buffer, &scratch, func() {
		buffer.Write([]byte("<flatMap>"))
	}, func() {
		buffer.Write([]byte("</flatMap>"))
	})

	// map entry
	e, closeFunc1 := m.Entry()
	e.Key("key").String("example-key1")
	e.Key("value").String("example1")
	closeFunc1()

	// map entry
	e, closeFunc2 := m.Entry()
	e.Key("key").String("example-key2")
	e.Key("value").String("example2")
	closeFunc2()

	// map entry
	e, closeFunc3 := m.Entry()
	e.Key("key").String("example-key3")
	e.Key("value").String("example3")
	closeFunc3()

	ex := []byte(`<flatMap><key>example-key1</key><value>example1</value></flatMap><flatMap><key>example-key2</key><value>example2</value></flatMap><flatMap><key>example-key3</key><value>example3</value></flatMap>`)
	if a := buffer.Bytes(); bytes.Compare(ex, a) != 0 {
		t.Errorf("expected %+q, but got %+q", ex, a)
	}
}
