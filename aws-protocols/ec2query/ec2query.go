package ec2query

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"bytes"

	"github.com/aws/smithy-go"
	internalquery "github.com/aws/smithy-go/aws-protocols/internal/query"
	internalxml "github.com/aws/smithy-go/aws-protocols/internal/xml"
	"github.com/aws/smithy-go/eventstream"
	"github.com/aws/smithy-go/middleware"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// Protocol implements aws.protocols#ec2Query.
type Protocol struct {
	eventstream.NoEventStream

	// Service API version (e.g. "2016-11-15"), sent as the "Version"
	// parameter in every request.
	Version string
}

var _ smithyhttp.ClientProtocol = (*Protocol)(nil)

// New returns an instance of the ec2Query protocol.
func New(version string) *Protocol {
	return &Protocol{Version: version}
}

// ID identifies the protocol.
func (*Protocol) ID() string {
	return "aws.protocols#ec2Query"
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

	ss := internalquery.NewShapeSerializer(middleware.GetOperationName(ctx), p.Version,
		func(o *internalquery.ShapeSerializerOptions) { o.EC2Mode = true },
	)
	in.Serialize(ss)

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

	inner, err := internalxml.ExtractElement(payload, middleware.GetOperationName(ctx)+"Response", true)
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
