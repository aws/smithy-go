package json

import (
	"bytes"
	"testing"
)

func TestEscapeStringBytes(t *testing.T) {
	jsonEncoder := NewEncoder()
	object := jsonEncoder.Object()

	object.Key("foo\"").String("bar")
	object.Key("faz").String("baz")
	object.Close()

	expected := []byte(`{"foo\"":"bar","faz":"baz"}`)
	actual := object.w.Bytes()
	if bytes.Compare(expected, actual) != 0 {
		t.Errorf("expected %+q, but got %+q", expected, actual)
	}
}
