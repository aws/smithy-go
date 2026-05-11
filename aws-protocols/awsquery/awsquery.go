package awsquery

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"

	"github.com/aws/smithy-go"
	internalquery "github.com/aws/smithy-go/aws-protocols/internal/query"
	internalxml "github.com/aws/smithy-go/aws-protocols/internal/xml"
	"github.com/aws/smithy-go/eventstream"
	"github.com/aws/smithy-go/middleware"
	"github.com/aws/smithy-go/traits"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// Protocol implements aws.protocols#awsQuery.
type Protocol struct {
	eventstream.NoEventStream

	// Service API version (e.g. "2020-01-08"), sent as the "Version" parameter
	// in every request.
	Version string

	// the query protocols do not have a "codec", they just inline a query
	// serializer and xml deserializer
}

var _ smithyhttp.ClientProtocol = (*Protocol)(nil)

// New returns an instance of the awsQuery protocol.
func New(version string) *Protocol {
	return &Protocol{Version: version}
}

// ID identifies the protocol.
func (*Protocol) ID() string {
	return "aws.protocols#awsQuery"
}

// SerializeRequest serializes a request for awsQuery.
func (p *Protocol) SerializeRequest(
	ctx context.Context,
	schema *smithy.OperationSchema,
	in smithy.Serializable,
	req *smithyhttp.Request,
) error {
	req.Method = http.MethodPost
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	ss := internalquery.NewShapeSerializer(middleware.GetOperationName(ctx), p.Version)
	in.Serialize(ss)

	sreq, err := req.SetStream(bytes.NewReader(ss.Bytes()))
	if err != nil {
		return fmt.Errorf("set stream: %w", err)
	}

	*req = *sreq
	return nil
}

// DeserializeResponse deserializes a response for awsQuery.
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

	inner, err := internalxml.ExtractElement(payload, middleware.GetOperationName(ctx)+"Result")
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

	// resolveError checks both direct shape name and @awsQueryError trait.
	perr, ok := resolveError(types, errorCode)
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

func resolveError(types *smithy.TypeRegistry, code string) (smithy.DeserializableError, bool) {
	if perr, ok := types.DeserializableError(code); ok {
		return perr, true
	}

	for _, entry := range types.Entries {
		if entry.Schema == nil {
			continue
		}

		if t, ok := smithy.SchemaTrait[*traits.AWSQueryError](entry.Schema); ok {
			if t.ErrorCode == code {
				v := entry.New()
				if perr, ok := v.(smithy.DeserializableError); ok {
					return perr, true
				}
			}
		}
	}

	return nil, false
}
