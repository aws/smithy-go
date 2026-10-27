package json

import (
	"testing"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/prelude"
)

func TestReadUnion_UnknownMember(t *testing.T) {
	cases := map[string]struct {
		payload    string
		wantMember string
		wantValue  string
	}{
		"object value":              {payload: `{"newKind":{"a":1}}`},
		"nested object value":       {payload: `{"newKind":{"a":{"b":[1,{"c":2}]}}}`},
		"array value":               {payload: `{"newKind":[1]}`},
		"empty object value":        {payload: `{"newKind":{}}`},
		"empty array value":         {payload: `{"newKind":[]}`},
		"string value":              {payload: `{"newKind":"x"}`},
		"number value":              {payload: `{"newKind":1}`},
		"bool value":                {payload: `{"newKind":true}`},
		"null value":                {payload: `{"newKind":null}`},
		"unknown then known":        {payload: `{"newKind":{"a":1},"lambda":"v"}`, wantMember: "lambda", wantValue: "v"},
		"known then unknown":        {payload: `{"lambda":"v","newKind":{"a":1}}`, wantMember: "lambda", wantValue: "v"},
		"unknown scalar then known": {payload: `{"newKind":"x","lambda":"v"}`, wantMember: "lambda", wantValue: "v"},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			d := NewShapeDeserializer([]byte(c.payload))
			defer d.Close()

			var member, value string
			err := smithy.ReadUnion(d, testSchemaInnerUnion, func(ms *smithy.Schema) error {
				member = ms.MemberName()
				return d.ReadString(ms, &value)
			})
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if member != c.wantMember || value != c.wantValue {
				t.Errorf("expected %q/%q, got %q/%q", c.wantMember, c.wantValue, member, value)
			}
		})
	}
}

func TestReadUnion_UnknownMemberInStruct(t *testing.T) {
	// The union sits inside a struct, so a mis-skip would corrupt the
	// reading of the members that follow it.
	for _, payload := range []string{
		`{"u":{"newKind":{"a":1}},"value":"after"}`,
		`{"u":{"newKind":[1]},"value":"after"}`,
		`{"u":{"newKind":"x"},"value":"after"}`,
	} {
		t.Run(payload, func(t *testing.T) {
			outer := smithy.NewSchema(smithy.ShapeID{
				Namespace: "com.test", Name: "Holder",
			}, smithy.ShapeTypeStructure, 2)
			outerUnion := outer.AddMember("u", testSchemaInnerUnion)
			outerValue := outer.AddMember("value", prelude.String)

			d := NewShapeDeserializer([]byte(payload))
			defer d.Close()

			var after string
			err := smithy.ReadStruct(d, outer, func(ms *smithy.Schema) error {
				switch ms {
				case outerUnion:
					return smithy.ReadUnion(d, testSchemaInnerUnion, func(*smithy.Schema) error {
						t.Error("union member callback must not fire for an unknown member")
						return nil
					})
				case outerValue:
					return d.ReadString(ms, &after)
				}
				return nil
			})
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if after != "after" {
				t.Errorf("expected member after the union to read %q, got %q", "after", after)
			}
		})
	}
}
