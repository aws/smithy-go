package httpbinding

import (
	"testing"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/traits"
)

func outputWith(memberName string, target *smithy.Schema, ts ...smithy.Trait) *smithy.Schema {
	out := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "Output"}, smithy.ShapeTypeStructure, 4)
	// a few members with no bindings, so the walk this caches is not trivial
	str := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "Str"}, smithy.ShapeTypeString, 0)
	out.AddMember("a", str)
	out.AddMember("b", str)
	out.AddMember(memberName, target, ts...)
	return out
}

func TestHasBlobPayload(t *testing.T) {
	blob := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "Blob"}, smithy.ShapeTypeBlob, 0)
	str := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "Str"}, smithy.ShapeTypeString, 0)

	for _, tt := range []struct {
		name string
		out  *smithy.Schema
		want bool
	}{
		{"nil output", nil, false},
		{"blob @httpPayload", outputWith("body", blob, &traits.HTTPPayload{}), true},
		// a string payload is materialized by copy, so the buffer is still poolable
		{"string @httpPayload", outputWith("body", str, &traits.HTTPPayload{}), false},
		{"blob without @httpPayload", outputWith("body", blob), false},
		{"no members", smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "E"}, smithy.ShapeTypeStructure, 0), false},
	} {
		if got := HasBlobPayload(tt.out); got != tt.want {
			t.Errorf("%s: got %v, want %v", tt.name, got, tt.want)
		}
	}
}

// The walk this caches does not allocate, so allocation cannot tell you whether
// the cache is live -- what it costs is time. Assert the mechanism instead: the
// extension is built once and returned by pointer thereafter, and HasBlobPayload
// reads it. BenchmarkHasBlobPayload measures what that saves.
func TestBindingExtIsCached(t *testing.T) {
	blob := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "Blob"}, smithy.ShapeTypeBlob, 0)
	out := outputWith("body", blob, &traits.HTTPPayload{})

	if !HasBlobPayload(out) {
		t.Fatal("expected schema to have blob payload")
	}

	first := getExt(out)
	if first == nil {
		t.Fatal("extension not populated")
	}
	if second := getExt(out); second != first {
		t.Error("extension rebuilt on second access; the slot is not caching")
	}
	if !first.hasBlobPayload {
		t.Error("cached extension holds the wrong answer")
	}
}

// Adding a member invalidates cached extensions. If that ever stops holding, a
// schema built up incrementally would answer from a stale walk.
func TestHasBlobPayloadRecomputedAfterAddMember(t *testing.T) {
	str := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "Str"}, smithy.ShapeTypeString, 0)
	blob := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "Blob"}, smithy.ShapeTypeBlob, 0)

	out := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "Output"}, smithy.ShapeTypeStructure, 2)
	out.AddMember("a", str)

	if HasBlobPayload(out) {
		t.Fatal("expected false before the payload member exists")
	}

	out.AddMember("body", blob, &traits.HTTPPayload{})

	if !HasBlobPayload(out) {
		t.Error("stale cached value after AddMember added a blob @httpPayload")
	}
}
