package json

import (
	"encoding/json"
	"reflect"
	"testing"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/document"
	"github.com/aws/smithy-go/prelude"
)

func strListSchema() *smithy.Schema {
	l := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "StrList"}, smithy.ShapeTypeList, 1)
	l.AddMember("member", prelude.String)
	return l
}

// A null element in a dense (non-sparse) list must not fail the whole
// response. The generated dense-list deserializers check ReadNil on each
// element and store the zero value, matching how the legacy generated JSON
// deserializers behaved. This exercises the runtime pieces that fix relies on.
func TestReadListDenseNullElement(t *testing.T) {
	s := strListSchema()

	// mirrors the generated dense-list deserializer body
	readList := func(payload string) ([]string, error) {
		d := NewShapeDeserializer([]byte(payload))
		defer d.Close()

		var out []string
		err := smithy.ReadList(d, s, func() error {
			if isNil, err := d.ReadNil(s.ListMember()); err != nil {
				return err
			} else if isNil {
				var zero string
				out = append(out, zero)
				return nil
			}

			var vv string
			if err := d.ReadString(s.ListMember(), &vv); err != nil {
				return err
			}
			out = append(out, vv)
			return nil
		})
		return out, err
	}

	cases := map[string]struct {
		payload string
		want    []string
	}{
		"single null element": {
			payload: `[null]`,
			want:    []string{""},
		},
		"null between values": {
			payload: `["a",null,"b"]`,
			want:    []string{"a", "", "b"},
		},
		"all null elements": {
			payload: `[null,null]`,
			want:    []string{"", ""},
		},
		"no nulls": {
			payload: `["a","b"]`,
			want:    []string{"a", "b"},
		},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			got, err := readList(c.payload)
			if err != nil {
				t.Fatalf("deserialize %q: %v", c.payload, err)
			}
			if len(got) != len(c.want) {
				t.Fatalf("length: expect %d, got %d (%v)", len(c.want), len(got), got)
			}
			for i := range c.want {
				if got[i] != c.want[i] {
					t.Errorf("element %d: expect %q, got %q", i, c.want[i], got[i])
				}
			}
		})
	}
}

func TestReadDocumentPreservesNumbers(t *testing.T) {
	payload := `{"i32":7,"big":9007199254740993,"max":9223372036854775807,"f":1.5e-3,"l":[-0,1e400]}`

	d := NewShapeDeserializer([]byte(payload))
	defer d.Close()

	var v document.Value
	if err := d.ReadDocument(prelude.Document, &v); err != nil {
		t.Fatalf("read document: %v", err)
	}

	ov, ok := v.(document.Opaque)
	if !ok {
		t.Fatalf("expect document.Opaque, got %T", v)
	}

	expect := map[string]any{
		"i32": json.Number("7"),
		"big": json.Number("9007199254740993"),
		"max": json.Number("9223372036854775807"),
		"f":   json.Number("1.5e-3"),
		"l":   []any{json.Number("-0"), json.Number("1e400")},
	}
	if !reflect.DeepEqual(expect, ov.Value) {
		t.Errorf("expect %#v, got %#v", expect, ov.Value)
	}
}
