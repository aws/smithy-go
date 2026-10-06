package json

import (
	"testing"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/prelude"
	"github.com/aws/smithy-go/traits"
)

func newMessageStruct(name, member string, ts ...smithy.Trait) (*smithy.Schema, *smithy.Schema) {
	s := smithy.NewSchema(smithy.ShapeID{Namespace: "com.test", Name: name}, smithy.ShapeTypeStructure, 1, ts...)
	return s, s.AddMember(member, prelude.String)
}

func readMessage(t *testing.T, s, msg *smithy.Schema, payload string) string {
	t.Helper()

	d := NewShapeDeserializer([]byte(payload))
	var got string
	err := smithy.ReadStruct(d, s, func(m *smithy.Schema) error {
		if m != msg {
			return nil
		}
		return d.ReadString(m, &got)
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	return got
}

func directReadMessage(t *testing.T, s, msg *smithy.Schema, payload string) string {
	t.Helper()

	d := NewShapeDeserializer([]byte(payload))
	var got string
	err := d.DirectReadStruct(s, func(m *smithy.Schema) error {
		if m != msg {
			return nil
		}
		return d.ReadString(m, &got)
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	return got
}

func TestErrorMessageCasing(t *testing.T) {
	for name, tc := range map[string]struct {
		modeled string
		isError bool
		payload string
		expect  string
	}{
		"modeled lower, wire upper": {"message", true, `{"Message":"hi"}`, "hi"},
		"modeled upper, wire lower": {"Message", true, `{"message":"hi"}`, "hi"},
		"modeled lower, wire lower": {"message", true, `{"message":"hi"}`, "hi"},
		"modeled upper, wire upper": {"Message", true, `{"Message":"hi"}`, "hi"},
		"escaped wire key":          {"message", true, `{"\u004dessage":"hi"}`, "hi"},
		"not an error, no fallback": {"message", false, `{"Message":"hi"}`, ""},
		"other casing not matched":  {"message", true, `{"MESSAGE":"hi"}`, ""},
		"other member not matched":  {"message", true, `{"msg":"hi"}`, ""},
	} {
		t.Run(name, func(t *testing.T) {
			var ts []smithy.Trait
			if tc.isError {
				ts = append(ts, &traits.Error{Type: "client"})
			}
			s, msg := newMessageStruct("E", tc.modeled, ts...)

			if actual := readMessage(t, s, msg, tc.payload); actual != tc.expect {
				t.Errorf("ReadStruct: expect %q, got %q", tc.expect, actual)
			}
			if actual := directReadMessage(t, s, msg, tc.payload); actual != tc.expect {
				t.Errorf("DirectReadStruct: expect %q, got %q", tc.expect, actual)
			}
		})
	}
}

func TestErrorMessageCasing_NestedNonErrorStruct(t *testing.T) {
	inner := smithy.NewSchema(smithy.ShapeID{Namespace: "com.test", Name: "Inner"}, smithy.ShapeTypeStructure, 1)
	innerMsg := inner.AddMember("message", prelude.String)
	outer := smithy.NewSchema(smithy.ShapeID{Namespace: "com.test", Name: "E"}, smithy.ShapeTypeStructure, 1,
		&traits.Error{Type: "client"})
	outerInner := outer.AddMember("inner", inner)

	d := NewShapeDeserializer([]byte(`{"inner":{"Message":"hi"}}`))
	var got string
	err := smithy.ReadStruct(d, outer, func(m *smithy.Schema) error {
		if m != outerInner {
			return nil
		}
		return smithy.ReadStruct(d, inner, func(im *smithy.Schema) error {
			if im != innerMsg {
				return nil
			}
			return d.ReadString(im, &got)
		})
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if got != "" {
		t.Errorf("expect no fallback in nested non-error struct, got %q", got)
	}
}
