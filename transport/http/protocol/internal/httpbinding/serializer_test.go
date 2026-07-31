package httpbinding

import (
	"context"
	"encoding/json"
	"io"
	"testing"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/prelude"
	"github.com/aws/smithy-go/traits"
	smithyhttp "github.com/aws/smithy-go/transport/http"
	internaljson "github.com/aws/smithy-go/transport/http/protocol/internal/json"
)

// payloadInput models an operation input whose only members are an
// @httpPayload structure and an @httpHeader, i.e. it has no unbound body
// members of its own. The payload structure contains a nested structure.
type payloadInput struct{}

func (payloadInput) Serialize(s smithy.ShapeSerializer) {
	out, payload, inner := payloadSchemas()

	s.WriteStruct(out)
	s.WriteString(out.Member("etag"), "__ETag__")

	s.WriteStruct(payload)
	s.WriteString(payload.Member("arn"), "__Arn__")
	s.WriteStruct(payload.Member("inner"))
	s.WriteString(inner.Member("changeType"), "DOWNGRADE")
	s.CloseStruct()
	// these come after the nested struct: if its closing brace is dropped
	// they end up nested inside of it
	s.WriteString(payload.Member("status"), "PENDING_APPROVAL")
	s.CloseStruct()

	s.CloseStruct()
}

func payloadSchemas() (out, payload, inner *smithy.Schema) {
	inner = smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "Inner"}, smithy.ShapeTypeStructure, 1)
	inner.AddMember("changeType", prelude.String)

	payload = smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "Payload"}, smithy.ShapeTypeStructure, 3)
	payload.AddMember("arn", prelude.String)
	payload.AddMember("inner", inner)
	payload.AddMember("status", prelude.String)

	out = smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "Input"}, smithy.ShapeTypeStructure, 2,
		&traits.HTTP{Method: "POST", URI: "/"})
	out.AddMember("payload", payload, &traits.HTTPPayload{})
	out.AddMember("etag", prelude.String, &traits.HTTPHeader{Name: "ETag"})
	return out, payload, inner
}

// An input with no body members of its own suppresses the top-level struct
// delimiters, since the body comes entirely from the @httpPayload member.
// That suppression must not be applied to structs nested inside the payload:
// prior to the fix for this, the first nested CloseStruct consumed the
// top-level suppression flag and its closing brace was dropped, so members
// following the nested struct were emitted inside of it.
func TestSerializeNestedStructInPayload(t *testing.T) {
	out, _, _ := payloadSchemas()

	req := smithyhttp.NewStackRequest().(*smithyhttp.Request)
	s, err := NewShapeSerializer(out, req, internaljson.NewShapeSerializer())
	if err != nil {
		t.Fatal(err)
	}

	in := payloadInput{}
	in.Serialize(s)
	if err := s.Build(nil, "application/json"); err != nil {
		t.Fatal(err)
	}

	built := req.Build(context.Background())
	body, err := io.ReadAll(built.Body)
	if err != nil {
		t.Fatal(err)
	}

	var got map[string]any
	if err := json.Unmarshal(body, &got); err != nil {
		t.Fatalf("unmarshal body %s: %v", body, err)
	}

	expect := map[string]any{
		"arn":    "__Arn__",
		"status": "PENDING_APPROVAL",
		"inner": map[string]any{
			"changeType": "DOWNGRADE",
		},
	}
	if !jsonEqual(got, expect) {
		t.Errorf("body mismatch\n\tgot:    %s\n\texpect: %v", body, expect)
	}
	if v := built.Header.Get("ETag"); v != "__ETag__" {
		t.Errorf("ETag: expect %q, got %q", "__ETag__", v)
	}
}

func jsonEqual(a, b any) bool {
	ab, err := json.Marshal(a)
	if err != nil {
		return false
	}
	bb, err := json.Marshal(b)
	if err != nil {
		return false
	}
	return string(ab) == string(bb)
}
