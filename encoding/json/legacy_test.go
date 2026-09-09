package json_test

import (
	"bytes"
	"testing"

	"github.com/aws/smithy-go/encoding/json"
	internaljson "github.com/aws/smithy-go/internal/encoding/json"
)

// The deprecated names must remain aliases of the moved implementation, not
// distinct types, or existing callers break.
var (
	_ json.Value    = internaljson.Value{}
	_ *json.Object  = (*internaljson.Object)(nil)
	_ *json.Array   = (*internaljson.Array)(nil)
	_ *json.Encoder = (*internaljson.Encoder)(nil)
)

// TestLegacyAliasCompat guards the deprecated Value/Object/Array/Encoder
// aliases: they must keep round-tripping through the moved
// internal/encoding/json writer.
func TestLegacyAliasCompat(t *testing.T) {
	encoder := json.NewEncoder()

	object := encoder.Object()
	object.Key("name").String("widget")
	object.Key("count").Long(3)

	list := object.Key("tags").Array()
	list.Value().String("a")
	list.Value().String("b")
	list.Close()

	object.Close()

	want := []byte(`{"name":"widget","count":3,"tags":["a","b"]}`)
	if got := encoder.Bytes(); !bytes.Equal(want, got) {
		t.Errorf("expected %s, got %s", want, got)
	}
}
