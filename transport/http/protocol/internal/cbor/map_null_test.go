package cbor

import (
	"reflect"
	"testing"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/prelude"
)

// Mirrors generated code for a dense map, which stores the zero value for an
// explicit null (or undefined).
func TestReadDenseMapNullValue(t *testing.T) {
	schema := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "M"}, smithy.ShapeTypeMap, 2)
	schema.AddMember("key", prelude.String)
	schema.AddMember("value", prelude.Integer)

	// {"a": 1, "b": null, "c": undefined, "d": 2}
	payload := []byte{
		0xa4,
		0x61, 'a', 0x01,
		0x61, 'b', 0xf6,
		0x61, 'c', 0xf7,
		0x61, 'd', 0x02,
	}
	d := NewShapeDeserializer(payload)

	v := map[string]int32{}
	var vv int32
	err := smithy.ReadMap(d, schema, func(k string) error {
		if isNil, err := d.ReadNil(schema.MapValue()); err != nil {
			return err
		} else if isNil {
			var zero int32
			v[k] = zero
			return nil
		}

		if err := d.ReadInt32(schema.MapValue(), &vv); err != nil {
			return err
		}
		v[k] = vv
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}

	expect := map[string]int32{"a": 1, "b": 0, "c": 0, "d": 2}
	if !reflect.DeepEqual(expect, v) {
		t.Errorf("expect %v, got %v", expect, v)
	}
}
