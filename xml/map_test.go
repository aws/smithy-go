package xml

import (
	"bytes"
	"testing"
)

func TestWrappedMap(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	m := newMap(buffer, &scratch)
	entry := m.Entry()
	entry.Key("key").String("example-key1")
	entry.Key("value").String("example1")
	entry.Close()

	entry = m.Entry()
	entry.Key("key").String("example-key2")
	entry.Key("value").String("example2")
	entry.Close()

	entry = m.Entry()
	entry.Key("key").String("example-key3")
	entry.Key("value").String("example3")
	entry.Close()

	e := []byte(`<entry><key>example-key1</key><value>example1</value></entry><entry><key>example-key2</key><value>example2</value></entry><entry><key>example-key3</key><value>example3</value></entry>`)
	if a := buffer.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}
}

func TestFlattenedMapWithCustomName(t *testing.T) {
	buffer := bytes.NewBuffer(nil)
	scratch := make([]byte, 64)

	m := newFlattenedMap(buffer, &scratch, "flatMap")
	entry := m.Entry()
	entry.Key("key").String("example-key1")
	entry.Key("value").String("example1")
	entry.Close()

	entry = m.Entry()
	entry.Key("key").String("example-key2")
	entry.Key("value").String("example2")
	entry.Close()

	entry = m.Entry()
	entry.Key("key").String("example-key3")
	entry.Key("value").String("example3")
	entry.Close()

	e := []byte(`<flatMap><key>example-key1</key><value>example1</value></flatMap><flatMap><key>example-key2</key><value>example2</value></flatMap><flatMap><key>example-key3</key><value>example3</value></flatMap>`)
	if a := buffer.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}
}
