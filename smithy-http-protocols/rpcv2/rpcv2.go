package rpcv2

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/eventstream"
	internalerrors "github.com/aws/smithy-go/internal/errors"
	smithyio "github.com/aws/smithy-go/io"
	"github.com/aws/smithy-go/middleware"
	internalcbor "github.com/aws/smithy-go/smithy-http-protocols/internal/cbor"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// Protocol implements an RPC v2 protocol.
type Protocol struct {
	options ProtocolOptions

	codec       smithy.Codec
	contentType string
	protocolID  string

	*eventstream.Codec
}

// ProtocolOptions configures a Protocol.
type ProtocolOptions struct {
	// When enabled, support reading legacy AWS query error codes in error
	// responses.
	//
	// See https://smithy.io/2.0/aws/protocols/aws-query-protocol.html#aws-protocols-awsquerycompatible-trait.
	UseQueryCompatible bool
}

// UseQueryCompatible enables support for AWS query compatibility.
func UseQueryCompatible(o *ProtocolOptions) {
	o.UseQueryCompatible = true
}

var _ smithyhttp.ClientProtocol = (*Protocol)(nil)

// NewCBOR returns an instance of the smithy.protocols#rpcv2Cbor protocol.
func NewCBOR(opts ...func(*ProtocolOptions)) *Protocol {
	var options ProtocolOptions
	for _, opt := range opts {
		opt(&options)
	}
	codec := &internalcbor.Codec{}
	return &Protocol{
		options:     options,
		codec:       codec,
		contentType: "application/cbor",
		protocolID:  "smithy.protocols#rpcv2Cbor",
		Codec: &eventstream.Codec{
			Codec:       codec,
			ContentType: "application/cbor",
		},
	}
}

// ID identifies the protocol.
func (p *Protocol) ID() string {
	return p.protocolID
}

// SerializeRequest serializes a request for rpcv2Cbor.
func (p *Protocol) SerializeRequest(
	ctx context.Context,
	schema *smithy.OperationSchema,
	in smithy.Serializable,
	req *smithyhttp.Request,
) error {
	req.Method = http.MethodPost
	req.URL.Path = fmt.Sprintf("/service/%s/operation/%s",
		middleware.GetServiceName(ctx), middleware.GetOperationName(ctx))
	req.Header.Set("Smithy-Protocol", "rpc-v2-cbor")
	req.Header.Set("Accept", "application/cbor")
	if p.options.UseQueryCompatible {
		req.Header.Set("X-Amzn-Query-Mode", "true")
	}

	if schema.IsInputEventStream() {
		req.Header.Set("Content-Type", "application/vnd.amazon.eventstream")
		return nil
	}

	ss := p.codec.Serializer()
	in.Serialize(ss)

	payload := ss.Bytes()
	if len(payload) == 0 {
		return nil
	}

	// operations targeting Unit MUST NOT have a body, check if we backfilled
	// an input
	if cs, ok := ss.(*internalcbor.ShapeSerializer); ok && cs.IsUnitShape() {
		return nil
	}

	req.Header.Set("Content-Type", p.contentType)

	sreq, err := req.SetStream(bytes.NewReader(payload))
	if err != nil {
		return fmt.Errorf("set stream: %w", err)
	}

	*req = *sreq
	return nil
}

// DeserializeResponse deserializes a response for rpcv2Cbor.
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

	if schema.IsOutputEventStream() {
		return nil
	}

	payload, err := io.ReadAll(resp.Body)
	if err != nil {
		return &smithy.DeserializationError{Err: err}
	}

	if len(payload) == 0 {
		return nil
	}

	sd := p.codec.Deserializer(payload)
	if err := out.Deserialize(sd); err != nil {
		return &smithy.DeserializationError{Err: err}
	}

	return nil
}

// HasInitialEventMessage is true because this is an RPC protocol.
func (*Protocol) HasInitialEventMessage() bool {
	return true
}

func (p *Protocol) deserializeError(types *smithy.TypeRegistry, response *smithyhttp.Response) error {
	var errorBuffer bytes.Buffer
	if _, err := io.Copy(&errorBuffer, response.Body); err != nil {
		return &smithy.DeserializationError{Err: fmt.Errorf("failed to copy error response body, %w", err)}
	}
	errorBody := errorBuffer.Bytes()

	errorCode := "UnknownError"
	errorMessage := errorCode

	var buff [1024]byte
	ringBuffer := smithyio.NewRingBuffer(buff[:])

	body := io.TeeReader(bytes.NewReader(errorBody), ringBuffer)
	bodyBytes, err := io.ReadAll(body)
	if err != nil {
		var snapshot bytes.Buffer
		io.Copy(&snapshot, ringBuffer)
		return &smithy.DeserializationError{
			Err:      fmt.Errorf("failed to read response body, %w", err),
			Snapshot: snapshot.Bytes(),
		}
	}

	bodyType, bodyMessage, err := internalcbor.GetProtocolErrorInfo(bodyBytes)
	if err != nil {
		var snapshot bytes.Buffer
		io.Copy(&snapshot, ringBuffer)
		return &smithy.DeserializationError{
			Err:      fmt.Errorf("failed to decode response body, %w", err),
			Snapshot: snapshot.Bytes(),
		}
	}

	if bodyType != "" {
		errorCode = bodyType
	}
	if bodyMessage != "" {
		errorMessage = bodyMessage
	}

	errorCode = internalerrors.SanitizeErrorCode(errorCode)

	var queryCode string
	var queryFault smithy.ErrorFault
	if p.options.UseQueryCompatible {
		queryHeader := response.Header.Get("X-Amzn-Query-Error")
		queryCode, queryFault = internalerrors.ParseQueryError(queryHeader)
	}

	perr, ok := types.DeserializableError(errorCode)
	if !ok {
		code := errorCode
		if queryCode != "" {
			code = queryCode
		}
		return &smithy.GenericAPIError{
			Code:    code,
			Message: errorMessage,
			Fault:   queryFault,
		}
	}

	if len(bodyBytes) > 0 {
		deser := p.codec.Deserializer(bodyBytes)
		if err := perr.Deserialize(deser); err != nil {
			return &smithy.DeserializationError{Err: err}
		}
	}

	if queryCode != "" {
		internalerrors.SetErrorCodeOverride(perr, queryCode)
	}

	return perr
}
