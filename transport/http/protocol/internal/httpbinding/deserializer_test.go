package httpbinding

import (
	"net/http"
	"testing"
	"time"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/prelude"
	"github.com/aws/smithy-go/traits"
	internaljson "github.com/aws/smithy-go/transport/http/protocol/internal/json"
)

// nested holds the deserialized values of the payload-targeted structure.
type nested struct {
	Str       string
	Bool      bool
	Int       int32
	Float     float64
	CreatedAt time.Time
	List      []string
	Map       map[string]string
	ETag      string
}

func nestedSchemas() (out, target *smithy.Schema) {
	strList := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "StrList"}, smithy.ShapeTypeList, 1)
	strList.AddMember("member", prelude.String)

	strMap := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "StrMap"}, smithy.ShapeTypeMap, 2)
	strMap.AddMember("key", prelude.String)
	strMap.AddMember("value", prelude.String)

	target = smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "Nested"}, smithy.ShapeTypeStructure, 7)
	target.AddMember("str", prelude.String)
	target.AddMember("bool", prelude.Boolean)
	target.AddMember("int", prelude.Integer)
	target.AddMember("float", prelude.Double)
	target.AddMember("createdAt", prelude.Timestamp, &traits.TimestampFormat{Format: "date-time"})
	target.AddMember("list", strList)
	target.AddMember("map", strMap)

	out = smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "Output"}, smithy.ShapeTypeStructure, 2)
	out.AddMember("payload", target, &traits.HTTPPayload{})
	out.AddMember("etag", prelude.String, &traits.HTTPHeader{Name: "ETag"})
	return out, target
}

// deserializeNested drives deserialization the way generated code does.
func deserializeNested(d smithy.ShapeDeserializer, out *smithy.Schema, v *nested) error {
	return smithy.ReadStruct(d, out, func(ms *smithy.Schema) error {
		if ms.MemberName() == "etag" {
			return d.ReadString(ms, &v.ETag)
		}
		if ms.MemberName() != "payload" {
			return nil
		}
		return smithy.ReadStruct(d, ms, func(ms *smithy.Schema) error {
			switch ms.MemberName() {
			case "str":
				return d.ReadString(ms, &v.Str)
			case "bool":
				return d.ReadBool(ms, &v.Bool)
			case "int":
				return d.ReadInt32(ms, &v.Int)
			case "float":
				return d.ReadFloat64(ms, &v.Float)
			case "createdAt":
				return d.ReadTime(ms, &v.CreatedAt)
			case "list":
				return smithy.ReadList(d, ms, func() error {
					var s string
					if err := d.ReadString(ms.ListMember(), &s); err != nil {
						return err
					}
					v.List = append(v.List, s)
					return nil
				})
			case "map":
				v.Map = map[string]string{}
				return smithy.ReadMap(d, ms, func(k string) error {
					var s string
					if err := d.ReadString(ms.MapValue(), &s); err != nil {
						return err
					}
					v.Map[k] = s
					return nil
				})
			}
			return nil
		})
	})
}

// A structure member bound with @httpPayload is a "binding" at the top level,
// but everything inside of it comes from the body. Prior to the fix for this,
// the deserializer left inBindings set while descending into the payload
// structure, causing nested members to be read out of (nonexistent) HTTP
// headers.
func TestDeserializeNestedPayloadStruct(t *testing.T) {
	payload := []byte(`{` +
		`"str":"foo",` +
		`"bool":true,` +
		`"int":7,` +
		`"float":1.5,` +
		`"createdAt":"2000-01-01T00:00:00Z",` +
		`"list":["a","b"],` +
		`"map":{"k":"v"}` +
		`}`)

	resp := &http.Response{
		StatusCode: 200,
		Header: http.Header{
			"Content-Type": []string{"application/json"},
			"Etag":         []string{"__ETag__"},
		},
	}

	out, _ := nestedSchemas()
	d := NewShapeDeserializer(resp, internaljson.NewShapeDeserializer(payload), payload)

	var v nested
	if err := deserializeNested(d, out, &v); err != nil {
		t.Fatalf("deserialize: %v", err)
	}

	if v.Str != "foo" {
		t.Errorf("str: expect %q, got %q", "foo", v.Str)
	}
	if !v.Bool {
		t.Errorf("bool: expect true, got false")
	}
	if v.Int != 7 {
		t.Errorf("int: expect 7, got %d", v.Int)
	}
	if v.Float != 1.5 {
		t.Errorf("float: expect 1.5, got %v", v.Float)
	}
	if expect := time.Date(2000, 1, 1, 0, 0, 0, 0, time.UTC); !v.CreatedAt.Equal(expect) {
		t.Errorf("createdAt: expect %v, got %v", expect, v.CreatedAt)
	}
	if len(v.List) != 2 || v.List[0] != "a" || v.List[1] != "b" {
		t.Errorf("list: expect [a b], got %v", v.List)
	}
	if len(v.Map) != 1 || v.Map["k"] != "v" {
		t.Errorf("map: expect map[k:v], got %v", v.Map)
	}
	if v.ETag != "__ETag__" {
		t.Errorf("etag: expect %q, got %q", "__ETag__", v.ETag)
	}
}
