package json

import (
	"testing"

	"github.com/aws/smithy-go"
)

// Schemas modeling a union member whose value is itself a union, e.g.
// bedrock-agentcore's TargetConfiguration -> mcp -> McpTargetConfiguration.
var (
	testSchemaString = smithy.NewSchema(smithy.ShapeID{
		Namespace: "smithy.api", Name: "String",
	}, smithy.ShapeTypeString, 0)

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
	testSchemaInnerUnion_Lambda = testSchemaInnerUnion.AddMember("lambda", testSchemaString)
	testSchemaOuterUnion_Mcp = testSchemaOuterUnion.AddMember("mcp", testSchemaInnerUnion)
}

// readNestedUnion mimics the calling pattern of SDK-generated code
// (smithy.ReadUnion in serde.go): repeatedly call ReadUnion until it returns
// no member, deserializing each member value in between.
func readNestedUnion(d *ShapeDeserializer) (member string, value string, err error) {
	err = smithy.ReadUnion(d, testSchemaOuterUnion, func(ms *smithy.Schema) error {
		member = ms.MemberName()
		return smithy.ReadUnion(d, testSchemaInnerUnion, func(inner *smithy.Schema) error {
			member += "." + inner.MemberName()
			return d.ReadString(inner, &value)
		})
	})
	return member, value, err
}

func TestReadUnion_NestedUnionValue(t *testing.T) {
	// A union whose member value is itself a union. Before the fix,
	// ReadUnion mistook the parent's union context for its own, skipped the
	// inner '{' and panicked in memberFromToken (slice bounds [1:0]).
	d := NewShapeDeserializer([]byte(`{"mcp":{"lambda":"arn:aws:lambda:fn"}}`))

	member, value, err := readNestedUnion(d)
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

func TestReadUnion_NestedUnionNullValue(t *testing.T) {
	// A union member with a null value is skipped entirely; the member
	// callback must not fire.
	d := NewShapeDeserializer([]byte(`{"mcp":null}`))

	member, _, err := readNestedUnion(d)
	if err != nil {
		t.Fatalf("expected success, got error: %v", err)
	}
	if member != "" {
		t.Errorf("expected no member, got %q", member)
	}
}

func TestReadUnion_FlatUnionStillWorks(t *testing.T) {
	// Regression guard: a plain (non-nested) union read.
	d := NewShapeDeserializer([]byte(`{"lambda":"v"}`))

	var member, value string
	err := smithy.ReadUnion(d, testSchemaInnerUnion, func(ms *smithy.Schema) error {
		member = ms.MemberName()
		return d.ReadString(ms, &value)
	})
	if err != nil {
		t.Fatalf("expected success, got error: %v", err)
	}
	if member != "lambda" || value != "v" {
		t.Errorf("expected lambda/v, got %q/%q", member, value)
	}
}
