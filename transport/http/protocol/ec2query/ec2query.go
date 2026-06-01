package ec2query

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"

	"github.com/aws/smithy-go"
	internalquery "github.com/aws/smithy-go/transport/http/protocol/internal/query"
	internalxml "github.com/aws/smithy-go/transport/http/protocol/internal/xml"
	internales "github.com/aws/smithy-go/internal/eventstream"
	"github.com/aws/smithy-go/middleware"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// ProtocolOptions configures aws.protocols#ec2Query.
type ProtocolOptions struct{}

// Protocol implements aws.protocols#ec2Query.
type Protocol struct {
	eventstream internales.NoEventStream

	version string
}

var _ smithyhttp.ClientProtocol = (*Protocol)(nil)

// New returns an instance of the ec2Query protocol. The service version is
// pulled from the ServiceVersion trait on the service schema.
func New(service *smithy.ServiceSchema, opts ...func(*ProtocolOptions)) *Protocol {
	var o ProtocolOptions
	for _, fn := range opts {
		fn(&o)
	}
	return &Protocol{version: service.Version}
}

// ID identifies the protocol.
func (*Protocol) ID() smithy.ShapeID {
	return smithy.ShapeID{Namespace: "aws.protocols", Name: "ec2Query"}
}

// HasInitialEventMessage implements [smithyhttp.ClientProtocol].
func (p *Protocol) HasInitialEventMessage() bool {
	return p.eventstream.HasInitialEventMessage()
}

// SerializeEventMessage implements [smithyhttp.ClientProtocol].
func (p *Protocol) SerializeEventMessage(schema, variant *smithy.Schema, v smithy.Serializable, w io.Writer) error {
	return p.eventstream.SerializeEventMessage(schema, variant, v, w)
}

// DeserializeEventMessage implements [smithyhttp.ClientProtocol].
func (p *Protocol) DeserializeEventMessage(schema *smithy.Schema, types *smithy.TypeRegistry, r io.Reader) (smithy.Deserializable, error) {
	return p.eventstream.DeserializeEventMessage(schema, types, r)
}

// SerializeInitialRequest implements [smithyhttp.ClientProtocol].
func (p *Protocol) SerializeInitialRequest(schema *smithy.Schema, v smithy.Serializable, w io.Writer) error {
	return p.eventstream.SerializeInitialRequest(schema, v, w)
}

// DeserializeInitialResponse implements [smithyhttp.ClientProtocol].
func (p *Protocol) DeserializeInitialResponse(schema *smithy.Schema, r io.Reader, out smithy.Deserializable) error {
	return p.eventstream.DeserializeInitialResponse(schema, r, out)
}

// SerializeRequest serializes a request for ec2Query.
func (p *Protocol) SerializeRequest(
	ctx context.Context,
	schema *smithy.OperationSchema,
	in smithy.Serializable,
	req *smithyhttp.Request,
) error {
	req.Method = http.MethodPost
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	if len(req.URL.Path) == 0 {
		req.URL.Path = "/"
	}

	ss := internalquery.NewShapeSerializer(middleware.GetOperationName(ctx), p.version,
		func(o *internalquery.ShapeSerializerOptions) { o.EC2Mode = true },
	)
	if schema.Input != nil {
		in.Serialize(ss)
	}

	sreq, err := req.SetStream(bytes.NewReader(ss.Bytes()))
	if err != nil {
		return fmt.Errorf("set stream: %w", err)
	}

	*req = *sreq
	return nil
}

// DeserializeResponse deserializes a response for ec2Query.
func (p *Protocol) DeserializeResponse(
	ctx context.Context,
	schema *smithy.OperationSchema,
	types *smithy.TypeRegistry,
	resp *smithyhttp.Response,
	out smithy.Deserializable,
) error {
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return p.deserializeError(types, resp)
	}

	payload, err := io.ReadAll(resp.Body)
	if err != nil {
		return &smithy.DeserializationError{Err: err}
	}

	if len(payload) == 0 {
		return nil
	}

	inner, err := internalxml.ExtractElement(payload, middleware.GetOperationName(ctx)+"Response")
	if err != nil {
		return &smithy.DeserializationError{Err: err}
	}

	if len(inner) == 0 {
		return nil
	}

	sd := internalxml.NewShapeDeserializer(inner)
	return out.Deserialize(sd)
}

func (p *Protocol) deserializeError(types *smithy.TypeRegistry, resp *smithyhttp.Response) error {
	payload, err := io.ReadAll(resp.Body)
	if err != nil {
		return &smithy.DeserializationError{Err: err}
	}

	errorCode, errorMessage, errorBody, err := internalxml.GetProtocolErrorInfo(payload)
	if err != nil {
		return &smithy.DeserializationError{Err: err}
	}

	// ec2query does not support @awsQueryError so this is a straight lookup
	perr, ok := types.DeserializableError(errorCode)
	if !ok {
		return &smithy.GenericAPIError{
			Code:    errorCode,
			Message: errorMessage,
		}
	}

	if len(errorBody) > 0 {
		sd := internalxml.NewShapeDeserializer(errorBody)
		if err := perr.Deserialize(sd); err != nil {
			return &smithy.DeserializationError{Err: err}
		}
	}

	return perr
}
