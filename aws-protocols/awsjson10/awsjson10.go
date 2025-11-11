package awsjson10

import (
	"context"
	"fmt"

	"github.com/aws/smithy-go"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// New returns an instance of the awsJson 1.0 protocol.
func New() *Protocol {
	return &Protocol{}
}

// Protocol implements aws.protocols#awsJson10.
type Protocol struct{}

var _ smithy.ClientProtocol = (*Protocol)(nil)

// ID identifies the protocol.
func (*Protocol) ID() string {
	return "aws.protocols#awsJson10"
}

// SerializeRequest serializes a request for AWS Json 1.0.
func (p *Protocol) SerializeRequest(ctx context.Context, operation smithy.Schema, input smithy.Serializable, request any) error {
	req, ok := request.(*smithyhttp.Request)
	if !ok {
		return fmt.Errorf("unexpected transport type %T", request)
	}

	req.Header.Set("X-Amz-Target", operation.ShapeName())
	req.Header.Set("Content-Type", "application/x-amz-json-1.0")
	if _, ok := smithy.SchemaTrait[*smithy.AWSQueryCompatible](operation); ok {
		req.Header.Set("X-Amzn-Query-Compatible", "true")
	}

	// ... codec

	return nil
}

func (p *Protocol) DeserializeResponse(ctx context.Context, operation smithy.Schema, response any, output smithy.Deserializable) error {
	return nil
}
