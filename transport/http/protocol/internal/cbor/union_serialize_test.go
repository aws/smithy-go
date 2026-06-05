package cbor

import (
	"testing"

	"github.com/aws/smithy-go"
	smithycbor "github.com/aws/smithy-go/encoding/cbor"
	"github.com/aws/smithy-go/prelude"
)

// Reproduces https://github.com/aws/aws-sdk-go-v2/issues/3441
// Union serialization within a struct double-writes the variant key because
// WriteUnion does not push ctxMapValue onto the stack after writing the
// variant name.
func TestWriteUnion_InStruct(t *testing.T) {
	// Set up schemas mimicking interconnect's CreateConnectionInput with an
	// AttachPoint union member.
	schemaAttachPoint := smithy.NewSchema(smithy.ShapeID{
		Namespace: "com.test", Name: "AttachPoint",
	}, smithy.ShapeTypeUnion, 1)
	schemaAttachPoint_dxgw := schemaAttachPoint.AddMember("directConnectGateway", prelude.String)

	schemaRequest := smithy.NewSchema(smithy.ShapeID{
		Namespace: "com.test", Name: "CreateConnectionRequest",
	}, smithy.ShapeTypeStructure, 2)
	schemaRequest_attachPoint := schemaRequest.AddMember("attachPoint", schemaAttachPoint)
	schemaRequest_bandwidth := schemaRequest.AddMember("bandwidth", prelude.String)

	// Serialize: struct { attachPoint: union{directConnectGateway: "dxgw-123"}, bandwidth: "1Gbps" }
	s := NewShapeSerializer()
	s.WriteStruct(schemaRequest)

	// This is what codegen produces for a union in a struct:
	s.WriteUnion(schemaRequest_attachPoint, schemaAttachPoint_dxgw)
	s.WriteString(schemaAttachPoint_dxgw, "dxgw-123")
	s.CloseUnion()

	s.WriteString(schemaRequest_bandwidth, "1Gbps")
	s.CloseStruct()

	// Decode and verify the wire format.
	// Expected: {"attachPoint": {"directConnectGateway": "dxgw-123"}, "bandwidth": "1Gbps"}
	got, err := smithycbor.Decode(s.Bytes())
	if err != nil {
		t.Fatalf("decode CBOR: %v", err)
	}

	m, ok := got.(smithycbor.Map)
	if !ok {
		t.Fatalf("expected Map, got %T", got)
	}

	// Check attachPoint is present and is a map
	ap, ok := m["attachPoint"]
	if !ok {
		t.Fatalf("missing 'attachPoint' key, got keys: %v", mapKeys(m))
	}
	apMap, ok := ap.(smithycbor.Map)
	if !ok {
		t.Fatalf("expected attachPoint to be Map, got %T: %v", ap, ap)
	}

	// The union value should be {"directConnectGateway": "dxgw-123"}
	val, ok := apMap["directConnectGateway"]
	if !ok {
		t.Fatalf("missing 'directConnectGateway' in union map, got keys: %v", mapKeys(apMap))
	}
	if str, ok := val.(smithycbor.String); !ok || string(str) != "dxgw-123" {
		t.Errorf("expected directConnectGateway = \"dxgw-123\", got %v", val)
	}

	// Check bandwidth
	bw, ok := m["bandwidth"]
	if !ok {
		t.Fatalf("missing 'bandwidth' key, got keys: %v", mapKeys(m))
	}
	if str, ok := bw.(smithycbor.String); !ok || string(str) != "1Gbps" {
		t.Errorf("expected bandwidth = \"1Gbps\", got %v", bw)
	}
}

func mapKeys(m smithycbor.Map) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	return keys
}
