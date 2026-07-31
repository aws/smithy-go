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

// A union targeted by @httpPayload is reached without going through
// ReadStruct's depth counter, so it is a second, independent way for bindings
// to leak into body members.
func TestDeserializeUnionPayload(t *testing.T) {
	u := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "U"}, smithy.ShapeTypeUnion, 2)
	u.AddMember("at", prelude.Timestamp, &traits.TimestampFormat{Format: "date-time"})
	u.AddMember("name", prelude.String)

	out := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "Out"}, smithy.ShapeTypeStructure, 2)
	out.AddMember("payload", u, &traits.HTTPPayload{})
	out.AddMember("etag", prelude.String, &traits.HTTPHeader{Name: "ETag"})

	payload := []byte(`{"at":"2000-01-01T00:00:00Z"}`)
	resp := &http.Response{
		StatusCode: 200,
		Header:     http.Header{"Etag": []string{"__ETag__"}},
	}
	d := NewShapeDeserializer(resp, internaljson.NewShapeDeserializer(payload), payload)

	var at time.Time
	var etag string
	err := smithy.ReadStruct(d, out, func(ms *smithy.Schema) error {
		switch ms.MemberName() {
		case "etag":
			return d.ReadString(ms, &etag)
		case "payload":
			return smithy.ReadUnion(d, ms, func(vs *smithy.Schema) error {
				if vs.MemberName() == "at" {
					return d.ReadTime(vs, &at)
				}
				return nil
			})
		}
		return nil
	})
	if err != nil {
		t.Fatalf("deserialize: %v", err)
	}
	if expect := time.Date(2000, 1, 1, 0, 0, 0, 0, time.UTC); !at.Equal(expect) {
		t.Errorf("at: expect %v, got %v", expect, at)
	}
	if etag != "__ETag__" {
		t.Errorf("etag: expect %q, got %q", "__ETag__", etag)
	}
}
