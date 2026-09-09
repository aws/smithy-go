package json

import (
	"bytes"
	"strings"
	"testing"
	"time"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/document"
	"github.com/aws/smithy-go/prelude"
	"github.com/aws/smithy-go/traits"
)

// widget is a hand-built modeled structure covering scalars, a nested
// structure, a list, a map, a timestamp, a blob, and a document.
type widget struct {
	Name      string
	Count     int32
	Tags      []string
	Attrs     map[string]string
	CreatedAt time.Time
	Payload   []byte
	Extra     document.Value
	Nested    *widgetNested
}

type widgetNested struct {
	Label string
}

var (
	schemaWidgetNested = smithy.NewSchema(
		smithy.ShapeID{Namespace: "com.test", Name: "WidgetNested"}, smithy.ShapeTypeStructure, 1)
	schemaWidgetNested_label = schemaWidgetNested.AddMember("label", prelude.String)

	schemaStrList = smithy.NewSchema(
		smithy.ShapeID{Namespace: "com.test", Name: "StrList"}, smithy.ShapeTypeList, 1)

	schemaStrMap = smithy.NewSchema(
		smithy.ShapeID{Namespace: "com.test", Name: "StrMap"}, smithy.ShapeTypeMap, 2)

	schemaWidget = smithy.NewSchema(
		smithy.ShapeID{Namespace: "com.test", Name: "Widget"}, smithy.ShapeTypeStructure, 8)
	schemaWidget_name      = schemaWidget.AddMember("name", prelude.String)
	schemaWidget_count     = schemaWidget.AddMember("count", prelude.Integer)
	schemaWidget_tags      = schemaWidget.AddMember("tags", schemaStrList)
	schemaWidget_attrs     = schemaWidget.AddMember("attrs", schemaStrMap)
	schemaWidget_createdAt = schemaWidget.AddMember("createdAt", prelude.Timestamp,
		&traits.TimestampFormat{Format: "date-time"})
	schemaWidget_payload = schemaWidget.AddMember("payload", prelude.Blob)
	schemaWidget_extra   = schemaWidget.AddMember("extra", prelude.Document)
	schemaWidget_nested  = schemaWidget.AddMember("nested", schemaWidgetNested)

	// jsonNamedWidget covers @jsonName: the member name differs from the
	// wire key.
	schemaJSONNamedWidget = smithy.NewSchema(
		smithy.ShapeID{Namespace: "com.test", Name: "JSONNamedWidget"}, smithy.ShapeTypeStructure, 1)
	schemaJSONNamedWidget_name = schemaJSONNamedWidget.AddMember("name", prelude.String,
		&traits.JSONName{Name: "n"})
)

func init() {
	schemaStrList.AddMember("member", prelude.String)
	schemaStrMap.AddMember("key", prelude.String)
	schemaStrMap.AddMember("value", prelude.String)
}

func (v *widget) Serialize(s smithy.ShapeSerializer) {
	s.WriteStruct(schemaWidget)
	v.SerializeMembers(s)
	s.CloseStruct()
}

func (v *widget) SerializeMembers(s smithy.ShapeSerializer) {
	s.WriteString(schemaWidget_name, v.Name)
	s.WriteInt32(schemaWidget_count, v.Count)

	s.WriteList(schemaWidget_tags)
	for _, tag := range v.Tags {
		s.WriteString(schemaWidget_tags.ListMember(), tag)
	}
	s.CloseList()

	s.WriteMap(schemaWidget_attrs)
	for k, val := range v.Attrs {
		s.WriteKey(schemaWidget_attrs.MapKey(), k)
		s.WriteString(schemaWidget_attrs.MapValue(), val)
	}
	s.CloseMap()

	s.WriteTime(schemaWidget_createdAt, v.CreatedAt)
	s.WriteBlob(schemaWidget_payload, v.Payload)
	s.WriteDocument(schemaWidget_extra, v.Extra)

	if v.Nested != nil {
		s.WriteStruct(schemaWidget_nested)
		v.Nested.SerializeMembers(s)
		s.CloseStruct()
	}
}

func (v *widgetNested) SerializeMembers(s smithy.ShapeSerializer) {
	s.WriteString(schemaWidgetNested_label, v.Label)
}

func (v *widgetNested) Deserialize(d smithy.ShapeDeserializer) error {
	return smithy.ReadStruct(d, schemaWidgetNested, func(ms *smithy.Schema) error {
		switch ms {
		case schemaWidgetNested_label:
			return d.ReadString(ms, &v.Label)
		}
		return nil
	})
}

func (v *widget) Deserialize(d smithy.ShapeDeserializer) error {
	return smithy.ReadStruct(d, schemaWidget, func(ms *smithy.Schema) error {
		switch ms {
		case schemaWidget_name:
			return d.ReadString(ms, &v.Name)
		case schemaWidget_count:
			return d.ReadInt32(ms, &v.Count)
		case schemaWidget_tags:
			return smithy.ReadList(d, ms, func() error {
				var s string
				if err := d.ReadString(ms.ListMember(), &s); err != nil {
					return err
				}
				v.Tags = append(v.Tags, s)
				return nil
			})
		case schemaWidget_attrs:
			v.Attrs = map[string]string{}
			return smithy.ReadMap(d, ms, func(k string) error {
				var s string
				if err := d.ReadString(ms.MapValue(), &s); err != nil {
					return err
				}
				v.Attrs[k] = s
				return nil
			})
		case schemaWidget_createdAt:
			return d.ReadTime(ms, &v.CreatedAt)
		case schemaWidget_payload:
			return d.ReadBlob(ms, &v.Payload)
		case schemaWidget_extra:
			return d.ReadDocument(ms, &v.Extra)
		case schemaWidget_nested:
			v.Nested = &widgetNested{}
			return v.Nested.Deserialize(d)
		}
		return nil
	})
}

// jsonNamedWidget exercises UseJSONName.
type jsonNamedWidget struct {
	Name string
}

func (v *jsonNamedWidget) Serialize(s smithy.ShapeSerializer) {
	s.WriteStruct(schemaJSONNamedWidget)
	s.WriteString(schemaJSONNamedWidget_name, v.Name)
	s.CloseStruct()
}

func (v *jsonNamedWidget) Deserialize(d smithy.ShapeDeserializer) error {
	return smithy.ReadStruct(d, schemaJSONNamedWidget, func(ms *smithy.Schema) error {
		if ms == schemaJSONNamedWidget_name {
			return d.ReadString(ms, &v.Name)
		}
		return nil
	})
}

func TestCodec_MarshalUnmarshal_Structure(t *testing.T) {
	in := &widget{
		Name:      "gizmo",
		Count:     3,
		Tags:      []string{"a", "b"},
		Attrs:     map[string]string{"color": "red"},
		CreatedAt: time.Date(2024, 1, 2, 3, 4, 5, 0, time.UTC),
		Payload:   []byte("hello"),
		Extra:     document.String("extra-value"),
		Nested:    &widgetNested{Label: "inner"},
	}

	c := Codec{}
	p, err := c.Marshal(in)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	var out widget
	if err := c.Unmarshal(p, &out); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	if out.Name != in.Name {
		t.Errorf("name: expected %q, got %q", in.Name, out.Name)
	}
	if out.Count != in.Count {
		t.Errorf("count: expected %d, got %d", in.Count, out.Count)
	}
	if len(out.Tags) != 2 || out.Tags[0] != "a" || out.Tags[1] != "b" {
		t.Errorf("tags: expected [a b], got %v", out.Tags)
	}
	if out.Attrs["color"] != "red" {
		t.Errorf("attrs: expected color=red, got %v", out.Attrs)
	}
	if !out.CreatedAt.Equal(in.CreatedAt) {
		t.Errorf("createdAt: expected %v, got %v", in.CreatedAt, out.CreatedAt)
	}
	if string(out.Payload) != "hello" {
		t.Errorf("payload: expected hello, got %q", out.Payload)
	}
	if s, ok := out.Extra.(document.Opaque); !ok || s.Value != "extra-value" {
		t.Errorf("extra: expected opaque extra-value, got %#v", out.Extra)
	}
	if out.Nested == nil || out.Nested.Label != "inner" {
		t.Errorf("nested: expected label inner, got %+v", out.Nested)
	}
}

func TestCodec_UseJSONName(t *testing.T) {
	in := &jsonNamedWidget{Name: "foo"}

	withName := Codec{Options: CodecOptions{UseJSONName: true}}
	p, err := withName.Marshal(in)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	if !bytes.Contains(p, []byte(`"n":"foo"`)) {
		t.Fatalf("expected wire key %q, got %s", "n", p)
	}

	var out jsonNamedWidget
	if err := withName.Unmarshal(p, &out); err != nil {
		t.Fatalf("unmarshal with UseJSONName: %v", err)
	}
	if out.Name != "foo" {
		t.Errorf("expected name foo, got %q", out.Name)
	}

	// Without UseJSONName the member name is used on the wire, and a
	// payload written with the jsonName key can no longer be matched.
	withoutName := Codec{}
	p2, err := withoutName.Marshal(in)
	if err != nil {
		t.Fatalf("marshal without UseJSONName: %v", err)
	}
	if !bytes.Contains(p2, []byte(`"name":"foo"`)) {
		t.Fatalf("expected wire key %q, got %s", "name", p2)
	}

	var out2 jsonNamedWidget
	if err := withoutName.Unmarshal(p, &out2); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if out2.Name != "" {
		t.Errorf("expected jsonName key to be ignored without UseJSONName, got %q", out2.Name)
	}
}

// Union round-trip via UnionVariantSerializer/UnionVariantDeserializer,
// mirroring what generated union member MarshalJSON/UnmarshalJSON delegate
// to.
var (
	schemaAnnouncement = smithy.NewSchema(
		smithy.ShapeID{Namespace: "com.test", Name: "Announcement"}, smithy.ShapeTypeUnion, 2)
	schemaAnnouncement_fire  = schemaAnnouncement.AddMember("fire", prelude.String)
	schemaAnnouncement_other = schemaAnnouncement.AddMember("other", prelude.String)
)

type announcementMemberFire struct {
	Value string
}

func (v *announcementMemberFire) Serialize(s smithy.ShapeSerializer) {
	s.WriteString(schemaAnnouncement_fire, v.Value)
}

func (v *announcementMemberFire) Deserialize(d smithy.ShapeDeserializer) error {
	return d.ReadString(schemaAnnouncement_fire, &v.Value)
}

func TestCodec_UnionVariant_Roundtrip(t *testing.T) {
	c := Codec{}
	in := &announcementMemberFire{Value: "evacuate"}

	p, err := c.Marshal(smithy.UnionVariantSerializer{
		Union:   schemaAnnouncement,
		Variant: schemaAnnouncement_fire,
		Value:   in,
	})
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	want := `{"fire":"evacuate"}`
	if string(p) != want {
		t.Fatalf("expected %s, got %s", want, p)
	}

	var out announcementMemberFire
	err = c.Unmarshal(p, smithy.UnionVariantDeserializer{
		Union:   schemaAnnouncement,
		Variant: schemaAnnouncement_fire,
		Value:   &out,
	})
	if err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if out.Value != "evacuate" {
		t.Errorf("expected evacuate, got %q", out.Value)
	}
}

func TestCodec_UnionVariant_WrongVariant(t *testing.T) {
	c := Codec{}
	p := []byte(`{"other":"x"}`)

	var out announcementMemberFire
	err := c.Unmarshal(p, smithy.UnionVariantDeserializer{
		Union:   schemaAnnouncement,
		Variant: schemaAnnouncement_fire,
		Value:   &out,
	})
	if err == nil {
		t.Fatal("expected an error for a mismatched union variant")
	}
	if !strings.Contains(err.Error(), "expected union variant fire, got other") {
		t.Errorf("expected a variant mismatch error, got %v", err)
	}
}

func TestCodec_Deserializer(t *testing.T) {
	c := Codec{}

	p, err := c.Marshal(&widget{Name: "no-copy", Count: 7})
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	var out widget
	if err := out.Deserialize(c.Deserializer(p)); err != nil {
		t.Fatalf("deserialize: %v", err)
	}
	if out.Name != "no-copy" || out.Count != 7 {
		t.Errorf("expected {no-copy 7}, got %+v", out)
	}
}
