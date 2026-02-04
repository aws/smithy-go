package awsjson10

import (
	"bytes"
	"context"
	"fmt"
	"net/http"

	"github.com/aws/smithy-go"
	smithyjson "github.com/aws/smithy-go/encoding/json"
	"github.com/aws/smithy-go/middleware"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// New returns an instance of the awsJson 1.0 protocol.
func New() *Protocol {
	return &Protocol{
		codec: &smithyjson.Codec{
			UseJSONName: false, // awsJson1.0 ignores this
		},
	}
}

// Protocol implements aws.protocols#awsJson10.
type Protocol struct {
	UseQueryCompatible bool

	codec *smithyjson.Codec
}

var _ smithy.ClientProtocol[*smithyhttp.Request, *smithyhttp.Response] = (*Protocol)(nil)

// ID identifies the protocol.
func (*Protocol) ID() string {
	return "aws.protocols#awsJson10"
}

// SerializeRequest serializes a request for AWS Json 1.0.
func (p *Protocol) SerializeRequest(ctx context.Context, op *smithy.Schema, in smithy.Serializable, req *smithyhttp.Request) error {
	req.Method = http.MethodPost
	req.Header.Set("X-Amz-Target", fmt.Sprintf("%s.%s", middleware.GetServiceName(ctx), middleware.GetOperationName(ctx)))
	req.Header.Set("Content-Type", "application/x-amz-json-1.0")
	if p.UseQueryCompatible {
		req.Header.Set("X-Amzn-Query-Compatible", "true")
	}

	ss := p.codec.Serializer()
	in.Serialize(ss)

	sreq, err := req.SetStream(bytes.NewReader(ss.Bytes()))
	if err != nil {
		return fmt.Errorf("set stream: %w", err)
	}

	*req = *sreq
	return nil
}

func (p *Protocol) DeserializeResponse(ctx context.Context, op *smithy.Schema, resp *smithyhttp.Response, out smithy.Deserializable) error {
	return nil
}
