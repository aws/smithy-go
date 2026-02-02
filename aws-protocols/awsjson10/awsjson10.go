package awsjson10

import (
	"context"

	"github.com/aws/smithy-go"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// New returns an instance of the awsJson 1.0 protocol.
func New() *Protocol {
	return &Protocol{}
}

// Protocol implements aws.protocols#awsJson10.
type Protocol struct {
	QueryCompatible bool
}

var _ smithy.ClientProtocol[*smithyhttp.Request, *smithyhttp.Response] = (*Protocol)(nil)

// ID identifies the protocol.
func (*Protocol) ID() string {
	return "aws.protocols#awsJson10"
}

// SerializeRequest serializes a request for AWS Json 1.0.
func (p *Protocol) SerializeRequest(ctx context.Context, op *smithy.Schema, in smithy.Serializable, req *smithyhttp.Request) error {
	req.Header.Set("X-Amz-Target", op.ID().Name)
	req.Header.Set("Content-Type", "application/x-amz-json-1.0")
	if p.QueryCompatible {
		req.Header.Set("X-Amzn-Query-Compatible", "true")
	}

	// ... codec

	return nil
}

func (p *Protocol) DeserializeResponse(ctx context.Context, op *smithy.Schema, resp *smithyhttp.Response, out smithy.Deserializable) error {
	return nil
}
