package cbor

import (
	"testing"

	"github.com/aws/smithy-go"
	smithycbor "github.com/aws/smithy-go/encoding/cbor"
	"github.com/aws/smithy-go/prelude"
)

var (
	testSchemaInnerUnion = smithy.NewSchema(smithy.ShapeID{
		Namespace: "com.test", Name: "InnerUnion",
	}, smithy.ShapeTypeUnion, 1)

	testSchemaOuterUnion = smithy.NewSchema(smithy.ShapeID{
		Namespace: "com.test", Name: "OuterUnion",
	}, smithy.ShapeTypeUnion, 1)

	testSchemaInnerUnion_Lambda *smithy.Schema
	testSchemaOuterUnion_Mcp    *smithy.Schema
)

func init() {
	testSchemaInnerUnion_Lambda = testSchemaInnerUnion.AddMember("lambda", prelude.String)
	testSchemaOuterUnion_Mcp = testSchemaOuterUnion.AddMember("mcp", testSchemaInnerUnion)
}

func TestReadUnion_NestedUnionValue(t *testing.T) {
	// CBOR equivalent of {"mcp":{"lambda":"arn:aws:lambda:fn"}}
	payload := smithycbor.Encode(smithycbor.Map{
		"mcp": smithycbor.Map{
			"lambda": smithycbor.String("arn:aws:lambda:fn"),
		},
	})

	d := NewShapeDeserializer(payload)

	var member, value string
	err := smithy.ReadUnion(d, testSchemaOuterUnion, func(ms *smithy.Schema) error {
		member = ms.MemberName()
		return smithy.ReadUnion(d, testSchemaInnerUnion, func(inner *smithy.Schema) error {
			member += "." + inner.MemberName()
			return d.ReadString(inner, &value)
		})
	})
	if err != nil {
		t.Fatalf("expected success, got error: %v", err)
	}
	if member != "mcp.lambda" {
		t.Errorf("expected member mcp.lambda, got %q", member)
	}
	if value != "arn:aws:lambda:fn" {
		t.Errorf("expected value arn:aws:lambda:fn, got %q", value)
	}
}
