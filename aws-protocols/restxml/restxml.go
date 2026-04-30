package restxml

import (
	"bytes"
	"context"
	"fmt"
	"io"

	"github.com/aws/smithy-go"
	internalhttpbinding "github.com/aws/smithy-go/aws-protocols/internal/httpbinding"
	internalxml "github.com/aws/smithy-go/aws-protocols/internal/xml"
	"github.com/aws/smithy-go/eventstream"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// Protocol implements aws.protocols#restXml.
type Protocol struct {
	*eventstream.Codec

	seropts func(*internalxml.ShapeSerializerOptions)
}

var _ smithyhttp.ClientProtocol = (*Protocol)(nil)

// New returns an instance of the aws.protocols#restXml protocol.
func New() *Protocol {
	return &Protocol{
		Codec: &eventstream.Codec{
			Codec:       internalxml.NewCodec(),
			ContentType: "application/xml",
		},
	}
}

// NewWithNamespace returns an instance of the aws.protocols#restXml protocol
// configured with the given XML namespace URI (and optional prefix) to emit
// as an xmlns attribute on the request body's root element. Corresponds to
// the service-level @xmlNamespace trait.
func NewWithNamespace(uri, prefix string) *Protocol {
	xmlOpts := func(o *internalxml.ShapeSerializerOptions) {
		o.RootNamespaceURI = uri
		o.RootNamespacePrefix = prefix
	}
	return &Protocol{
		Codec: &eventstream.Codec{
			Codec:       internalxml.NewCodec(xmlOpts),
			ContentType: "application/xml",
		},
		seropts: xmlOpts,
	}
}

// ID identifies the protocol.
func (*Protocol) ID() string {
	return "aws.protocols#restXml"
}

// HasInitialEventMessage implements [smithyhttp.ClientProtocol].
func (*Protocol) HasInitialEventMessage() bool {
	return false
}

// SerializeRequest serializes a request for restxml.
func (p *Protocol) SerializeRequest(
	ctx context.Context,
	op *smithy.OperationSchema,
	in smithy.Serializable,
	req *smithyhttp.Request,
) error {
	serializer, err := internalhttpbinding.NewShapeSerializer(op.Schema, req, p.newSerializer())
	if err != nil {
		return err
	}

	in.Serialize(serializer)

	contentType := "application/xml"
	if op.IsInputEventStream() {
		contentType = "application/vnd.amazon.eventstream"
	}
	if err := serializer.Build(in, contentType); err != nil {
		return fmt.Errorf("build request: %w", err)
	}

	return nil
}

// DeserializeResponse deserializes a response for restXml.
func (p *Protocol) DeserializeResponse(
	ctx context.Context,
	op *smithy.OperationSchema,
	types *smithy.TypeRegistry,
	resp *smithyhttp.Response,
	out smithy.Deserializable,
) error {
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return p.deserializeError(types, resp)
	}

	if so, ok := out.(smithy.StreamingOutput); ok {
		so.SetPayloadStream(resp.Body)
		deser := internalhttpbinding.NewShapeDeserializer(resp.Response, deser(nil, op.Output), nil)
		if err := out.Deserialize(deser); err != nil {
			return &smithy.DeserializationError{Err: err}
		}
		return nil
	}

	if op.IsOutputEventStream() {
		deser := internalhttpbinding.NewShapeDeserializer(resp.Response, deser(nil, op.Output), nil)
		if err := out.Deserialize(deser); err != nil {
			return &smithy.DeserializationError{Err: err}
		}
		return nil
	}

	payload, err := io.ReadAll(resp.Body)
	if err != nil {
		return &smithy.DeserializationError{Err: err}
	}

	deser := internalhttpbinding.NewShapeDeserializer(resp.Response, deser(payload, op.Output), payload)
	if err := out.Deserialize(deser); err != nil {
		return &smithy.DeserializationError{Err: err}
	}

	return nil
}

func (p *Protocol) newSerializer() *internalxml.ShapeSerializer {
	if p.seropts != nil {
		return internalxml.NewShapeSerializer(p.seropts)
	}
	return internalxml.NewShapeSerializer()
}

func (p *Protocol) deserializeError(types *smithy.TypeRegistry, resp *smithyhttp.Response) error {
	var buf bytes.Buffer
	if _, err := io.Copy(&buf, resp.Body); err != nil {
		return &smithy.DeserializationError{Err: fmt.Errorf("read error response body: %w", err)}
	}
	payload := buf.Bytes()

	errorCode, errorMessage, errorBody, err := internalxml.GetProtocolErrorInfo(payload)
	if err != nil {
		return &smithy.DeserializationError{Err: err}
	}

	perr, ok := types.DeserializableError(errorCode)
	if !ok {
		return &smithy.GenericAPIError{
			Code:    errorCode,
			Message: errorMessage,
		}
	}

	if len(errorBody) > 0 {
		deser := internalhttpbinding.NewShapeDeserializer(resp.Response, internalxml.NewShapeDeserializer(errorBody), payload)
		if err := perr.Deserialize(deser); err != nil {
			return &smithy.DeserializationError{Err: err}
		}
	}

	return perr
}

// returns a ShapeDeserializer over the (possibly empty) payload, wrapped in
// a synthetic outer element whose name matches the operation output shape so
// the schema-serde XML deserializer can open it uniformly
func deser(payload []byte, out *smithy.Schema) smithy.ShapeDeserializer {
	if len(payload) != 0 {
		return internalxml.NewShapeDeserializer(payload)
	}

	name := "Response"
	if out != nil {
		if n := out.ID().Name; n != "" {
			name = n
		}
	}
	payload = []byte("<" + name + "></" + name + ">")
	return internalxml.NewShapeDeserializer(payload)
}
