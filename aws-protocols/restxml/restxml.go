package restxml

import (
	"bytes"
	"context"
	"fmt"
	"io"

	"github.com/aws/smithy-go"
	internalhttpbinding "github.com/aws/smithy-go/aws-protocols/internal/httpbinding"
	internalxml "github.com/aws/smithy-go/aws-protocols/internal/xml"
	internales "github.com/aws/smithy-go/internal/eventstream"
	"github.com/aws/smithy-go/traits"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// Protocol implements aws.protocols#restXml.
type Protocol struct {
	eventstream *internales.Codec

	seropts func(*internalxml.ShapeSerializerOptions)
}

// ProtocolOptions configures aws.protocols#restXml.
type ProtocolOptions struct{}

var _ smithyhttp.ClientProtocol = (*Protocol)(nil)

// New returns an instance of the aws.protocols#restXml protocol. If the
// service schema carries an xmlNamespace trait, it is applied as the root
// xmlns attribute on serialized request bodies.
func New(service *smithy.ServiceSchema, opts ...func(*ProtocolOptions)) *Protocol {
	var o ProtocolOptions
	for _, fn := range opts {
		fn(&o)
	}
	var xmlOpts func(*internalxml.ShapeSerializerOptions)
	if ns, ok := smithy.SchemaTrait[*traits.XMLNamespace](service.Schema); ok {
		xmlOpts = func(o *internalxml.ShapeSerializerOptions) {
			o.RootNamespaceURI = ns.URI
			o.RootNamespacePrefix = ns.Prefix
		}
	}

	return &Protocol{
		eventstream: &internales.Codec{
			Serializer: func() smithy.ShapeSerializer {
				if xmlOpts != nil {
					return internalxml.NewShapeSerializer(xmlOpts)
				}
				return internalxml.NewShapeSerializer()
			},
			Deserializer: func(p []byte) smithy.ShapeDeserializer { return internalxml.NewShapeDeserializer(p) },
			ContentType:  "application/xml",
		},
		seropts: xmlOpts,
	}
}

// ID identifies the protocol.
func (*Protocol) ID() smithy.ShapeID {
	return smithy.ShapeID{Namespace: "aws.protocols", Name: "restXml"}
}

// HasInitialEventMessage implements [smithyhttp.ClientProtocol].
func (*Protocol) HasInitialEventMessage() bool {
	return false
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

	if op.Input != nil {
		in.Serialize(serializer)
	}

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

	if op.Output == nil {
		return nil
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
