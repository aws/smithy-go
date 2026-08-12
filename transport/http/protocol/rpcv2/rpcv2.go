package rpcv2

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"

	"github.com/aws/smithy-go"
	internalerrors "github.com/aws/smithy-go/internal/errors"
	internales "github.com/aws/smithy-go/internal/eventstream"
	internalsync "github.com/aws/smithy-go/internal/sync"
	smithyio "github.com/aws/smithy-go/io"
	"github.com/aws/smithy-go/middleware"
	"github.com/aws/smithy-go/traits"
	smithyhttp "github.com/aws/smithy-go/transport/http"
	internalcbor "github.com/aws/smithy-go/transport/http/protocol/internal/cbor"
	internaljson "github.com/aws/smithy-go/transport/http/protocol/internal/json"
)

// Protocol implements an RPC v2 protocol.
//
// RPCv2 protocol family:
//   - CBOR: https://smithy.io/2.0/additional-specs/protocols/smithy-rpc-v2.html
//   - JSON: smithy.protocols#rpcv2Json
//
// The two variants share their entire HTTP shape -- method, URI, the
// Smithy-Protocol header, and the error discovery flow -- and differ only in the
// payload codec, so they are one implementation parameterized by [codec].
type Protocol struct {
	queryCompatible bool
	serviceName     string

	codec       codec
	eventstream *internales.Codec
	bufs        *internalsync.BufferPool
}

// codec captures everything that differs between the RPCv2 variants.
type codec struct {
	// name is the shape name of the protocol trait, e.g. "rpcv2Cbor".
	name string

	// smithyProtocol is the value of the Smithy-Protocol header, e.g.
	// "rpc-v2-cbor".
	smithyProtocol string

	// contentType is the media type of the payload, used for both Content-Type
	// and Accept.
	contentType string

	newSerializer   func() shapeSerializer
	newDeserializer func([]byte) smithy.ShapeDeserializer

	// errorInfo extracts the error type and message from an HTTP response
	// payload.
	errorInfo func(payload []byte, header http.Header) (typ, message string, err error)

	// eventErrorInfo extracts the error type and message from an event
	// stream error event payload, which carries no HTTP header.
	eventErrorInfo func(payload []byte) (typ, message string, err error)
}

// shapeSerializer is the subset of the concrete codec serializers this protocol
// needs beyond [smithy.ShapeSerializer].
type shapeSerializer interface {
	smithy.ShapeSerializer

	Bytes() []byte
	IsUnitShape() bool
}

var _ smithyhttp.ClientProtocol = (*Protocol)(nil)

// ProtocolOptions configures the RPC v2 protocols.
type ProtocolOptions struct{}

// NewCBOR returns an instance of the smithy.protocols#rpcv2Cbor protocol.
func NewCBOR(service *smithy.ServiceSchema, opts ...func(*ProtocolOptions)) *Protocol {
	var o ProtocolOptions
	for _, fn := range opts {
		fn(&o)
	}
	return newProtocol(service, codec{
		name:           "rpcv2Cbor",
		smithyProtocol: "rpc-v2-cbor",
		contentType:    "application/cbor",
		newSerializer: func() shapeSerializer {
			return internalcbor.NewShapeSerializer()
		},
		newDeserializer: func(p []byte) smithy.ShapeDeserializer {
			return internalcbor.NewShapeDeserializer(p)
		},
		errorInfo: func(payload []byte, _ http.Header) (string, string, error) {
			return internalcbor.GetProtocolErrorInfo(payload)
		},
		eventErrorInfo: internalcbor.GetProtocolErrorInfo,
	})
}

// NewJSON returns an instance of the smithy.protocols#rpcv2Json protocol.
func NewJSON(service *smithy.ServiceSchema, opts ...func(*ProtocolOptions)) *Protocol {
	var o ProtocolOptions
	for _, fn := range opts {
		fn(&o)
	}
	return newProtocol(service, codec{
		name:           "rpcv2Json",
		smithyProtocol: "rpc-v2-json",
		contentType:    "application/json",
		newSerializer: func() shapeSerializer {
			return internaljson.NewShapeSerializer(rpcv2JsonOptions)
		},
		newDeserializer: func(p []byte) smithy.ShapeDeserializer {
			return internaljson.NewShapeDeserializer(p, rpcv2JsonOptions)
		},
		errorInfo:      jsonErrorInfo,
		eventErrorInfo: internaljson.EventStreamErrorInfo,
	})
}

// rpcv2Json encodes bigInteger and bigDecimal as JSON strings so that a peer
// which coerces JSON numbers to a double cannot silently drop precision, and it
// always encodes timestamps as epoch-seconds regardless of @timestampFormat.
func rpcv2JsonOptions(o *internaljson.Options) {
	o.UseStringForArbitraryPrecision = true
	o.IgnoreTimestampFormat = true
}

// rpcv2Json reports the error shape in the body's __type field, the same as the
// awsJson protocols.
func jsonErrorInfo(payload []byte, header http.Header) (string, string, error) {
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.UseNumber()

	info, err := internaljson.GetProtocolErrorInfo(decoder)
	if err != nil {
		return "", "", err
	}

	typ, _ := internaljson.ResolveProtocolErrorType(header.Get("X-Amzn-ErrorType"), info)
	return typ, info.Message, nil
}

func newProtocol(service *smithy.ServiceSchema, c codec) *Protocol {
	_, qc := smithy.SchemaTrait[*traits.AWSQueryCompatible](service.Schema)
	return &Protocol{
		queryCompatible: qc,
		serviceName:     service.Schema.ID().Name,
		codec:           c,
		eventstream: &internales.Codec{
			Serializer:   func() smithy.ShapeSerializer { return c.newSerializer() },
			Deserializer: c.newDeserializer,
			ContentType:  c.contentType,
			ErrorInfo:    c.eventErrorInfo,
		},
		bufs: internalsync.NewBufferPool(),
	}
}

// ID identifies the protocol.
func (p *Protocol) ID() smithy.ShapeID {
	return smithy.ShapeID{Namespace: "smithy.protocols", Name: p.codec.name}
}

// SerializeRequest serializes a request for an RPC v2 protocol.
func (p *Protocol) SerializeRequest(
	ctx context.Context,
	schema *smithy.OperationSchema,
	in smithy.Serializable,
	req *smithyhttp.Request,
) error {
	req.Method = http.MethodPost
	req.URL.Path = fmt.Sprintf("/service/%s/operation/%s",
		p.serviceName, middleware.GetOperationName(ctx))
	req.Header.Set("Smithy-Protocol", p.codec.smithyProtocol)
	req.Header.Set("Accept", p.codec.contentType)
	if p.queryCompatible {
		req.Header.Set("X-Amzn-Query-Mode", "true")
	}

	if schema.IsInputEventStream() {
		req.Header.Set("Content-Type", "application/vnd.amazon.eventstream")
		return nil
	}

	if schema.Input == nil {
		return nil
	}

	ss := p.codec.newSerializer()
	in.Serialize(ss)

	payload := ss.Bytes()
	if len(payload) == 0 {
		return nil
	}

	if ss.IsUnitShape() {
		return nil
	}

	req.Header.Set("Content-Type", p.codec.contentType)

	sreq, err := req.SetStream(bytes.NewReader(payload))
	if err != nil {
		return fmt.Errorf("set stream: %w", err)
	}

	*req = *sreq
	return nil
}

// DeserializeResponse deserializes a response for an RPC v2 protocol.
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
		if schema.IsInputEventStream() {
			if err := p.eventstream.RequireBidiHTTP2(resp.Proto, resp.ProtoMajor); err != nil {
				return err
			}
		}
		return nil
	}

	// NOTE: payload aliases the pooled buffer, so the deserializer must not
	// retain references into it -- everything it yields to the caller is
	// copied out today.
	buf, err := p.bufs.Get(resp.Body)
	if err != nil {
		return &smithy.DeserializationError{Err: err}
	}
	defer p.bufs.Put(buf)

	payload := buf.Bytes()

	if len(payload) == 0 {
		return nil
	}

	sd := p.codec.newDeserializer(payload)
	if err := out.Deserialize(sd); err != nil {
		return &smithy.DeserializationError{Err: err}
	}

	return nil
}

// HasInitialEventMessage is true because this is an RPC protocol.
func (*Protocol) HasInitialEventMessage() bool {
	return true
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

	bodyType, bodyMessage, err := p.codec.errorInfo(bodyBytes, response.Header)
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
	if p.queryCompatible {
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
		deser := p.codec.newDeserializer(bodyBytes)
		if err := perr.Deserialize(deser); err != nil {
			return &smithy.DeserializationError{Err: err}
		}
	}

	if queryCode != "" {
		internalerrors.SetErrorCodeOverride(perr, queryCode)
	}

	return perr
}
