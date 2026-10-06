package json

import (
	"testing"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/prelude"
)

// AWS JSON error bodies commonly use "Message" (capital M) while the shape
// member is "message". Pre-schema codegen accepted both; schema deserialization
// must too (aws-sdk-go-v2#3581).
func TestDirectReadStruct_MessageCapitalM(t *testing.T) {
	schema := smithy.NewSchema(smithy.ShapeID{
		Namespace: "com.test", Name: "AccessDeniedException",
	}, smithy.ShapeTypeStructure, 1)
	memberMessage := schema.AddMember("message", prelude.String)

	d := NewShapeDeserializer([]byte(`{"Message":"User is not authorized"}`))
	var got string
	err := d.DirectReadStruct(schema, func(ms *smithy.Schema) error {
		if ms != memberMessage {
			t.Fatalf("unexpected member %q", ms.MemberName())
		}
		return d.ReadString(ms, &got)
	})
	if err != nil {
		t.Fatalf("DirectReadStruct: %v", err)
	}
	if got != "User is not authorized" {
		t.Fatalf("message = %q, want %q", got, "User is not authorized")
	}
}

func TestDirectReadStruct_messageLowercase(t *testing.T) {
	schema := smithy.NewSchema(smithy.ShapeID{
		Namespace: "com.test", Name: "AccessDeniedException",
	}, smithy.ShapeTypeStructure, 1)
	memberMessage := schema.AddMember("message", prelude.String)

	d := NewShapeDeserializer([]byte(`{"message":"ok"}`))
	var got string
	err := d.DirectReadStruct(schema, func(ms *smithy.Schema) error {
		if ms != memberMessage {
			t.Fatalf("unexpected member %q", ms.MemberName())
		}
		return d.ReadString(ms, &got)
	})
	if err != nil {
		t.Fatalf("DirectReadStruct: %v", err)
	}
	if got != "ok" {
		t.Fatalf("message = %q, want %q", got, "ok")
	}
}
