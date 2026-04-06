package eventstream

import (
	"bytes"
	"fmt"
	"io"

	"github.com/aws/smithy-go"
)

// Codec orchestrates event stream message serde for protocols that use the
// standard event stream binary framing. All existing AWS protocols (and the
// Smithy rpcv2Cbor protocol) use this framing.
type Codec struct {
	Codec       smithy.Codec
	ContentType string

	encoder    *Encoder
	decoder    *Decoder
	payloadBuf []byte
}

func (c *Codec) enc() *Encoder {
	if c.encoder == nil {
		c.encoder = NewEncoder()
	}
	return c.encoder
}

func (c *Codec) dec() *Decoder {
	if c.decoder == nil {
		c.decoder = NewDecoder()
	}
	return c.decoder
}

// SerializeEventMessage serializes an event to the input stream.
func (c *Codec) SerializeEventMessage(schema, variant *smithy.Schema, v smithy.Serializable, w io.Writer) error {
	var msg Message

	inner := c.Codec.Serializer()
	ss := NewShapeSerializer(&msg, inner)

	v.Serialize(ss)

	msg.Headers.Set(MessageTypeHeader, StringValue(EventMessageType))
	msg.Headers.Set(EventTypeHeader, StringValue(variant.MemberName()))

	if ct := ss.ContentType(); ct != "" {
		msg.Headers.Set(ContentTypeHeader, StringValue(ct))
	} else if payload := inner.Bytes(); len(payload) > 0 {
		msg.Payload = payload
		msg.Headers.Set(ContentTypeHeader, StringValue(c.ContentType))
	}

	return c.enc().Encode(w, msg)
}

// DeserializeEventMessage reads an event from the output stream. Returns
// io.EOF when the stream is complete.
func (c *Codec) DeserializeEventMessage(schema *smithy.Schema, types *smithy.TypeRegistry, r io.Reader) (smithy.Deserializable, error) {
	for {
		c.payloadBuf = c.payloadBuf[0:0]
		msg, err := c.dec().Decode(r, c.payloadBuf)
		if err != nil {
			if isEOF(err) {
				return nil, io.EOF
			}
			return nil, fmt.Errorf("decode event: %w", err)
		}

		msgType := msg.Headers.Get(MessageTypeHeader)
		if msgType == nil {
			return nil, fmt.Errorf("missing %s header", MessageTypeHeader)
		}

		switch msgType.String() {
		case EventMessageType:
			event, err := c.deserializeEvent(schema, types, &msg)
			if err != nil {
				return nil, err
			}
			return event, nil
		case ExceptionMessageType:
			return nil, c.deserializeException(schema, types, &msg)
		case ErrorMessageType:
			return nil, deserializeError(&msg)
		default:
			mc := msg.Clone()
			return nil, &UnknownMessageError{
				Type:    msgType.String(),
				Message: &mc,
			}
		}
	}
}

func (c *Codec) deserializeEvent(schema *smithy.Schema, types *smithy.TypeRegistry, msg *Message) (smithy.Deserializable, error) {
	eventType := msg.Headers.Get(EventTypeHeader)
	if eventType == nil {
		return nil, fmt.Errorf("missing %s header", EventTypeHeader)
	}

	member := schema.Member(eventType.String())
	if member == nil {
		return c.unknownEvent(eventType.String(), msg)
	}

	entry, ok := types.LookupEntry(member.TargetID().String())
	if !ok {
		return c.unknownEvent(eventType.String(), msg)
	}

	instance, ok := entry.New().(smithy.Deserializable)
	if !ok {
		return nil, fmt.Errorf("event type %s is not deserializable", eventType.String())
	}

	inner := c.Codec.Deserializer(msg.Payload)
	ed := NewShapeDeserializer(msg, inner)
	if err := instance.Deserialize(ed); err != nil {
		return nil, fmt.Errorf("deserialize event %s: %w", eventType.String(), err)
	}

	return instance, nil
}

func (c *Codec) unknownEvent(tag string, msg *Message) (*UnknownUnionMember, error) {
	var buf bytes.Buffer
	c.enc().Encode(&buf, *msg)
	return &UnknownUnionMember{Tag: tag, Value: buf.Bytes()}, nil
}

func (c *Codec) deserializeException(schema *smithy.Schema, types *smithy.TypeRegistry, msg *Message) error {
	exType := msg.Headers.Get(ExceptionTypeHeader)
	if exType == nil {
		return fmt.Errorf("missing %s header", ExceptionTypeHeader)
	}

	var id string
	if member := schema.Member(exType.String()); member != nil {
		id = member.TargetID().String()
	} else {
		id = exType.String()
	}

	perr, ok := types.DeserializableError(id)
	if !ok {
		return &smithy.GenericAPIError{
			Code:    exType.String(),
			Message: "unknown exception",
		}
	}

	inner := c.Codec.Deserializer(msg.Payload)
	ed := NewShapeDeserializer(msg, inner)
	if err := perr.Deserialize(ed); err != nil {
		return fmt.Errorf("deserialize exception %s: %w", exType.String(), err)
	}

	return perr
}

// SerializeInitialRequest serializes the operation input as the first event
// stream message with :event-type "initial-request".
func (c *Codec) SerializeInitialRequest(schema *smithy.Schema, v smithy.Serializable, w io.Writer) error {
	ss := c.Codec.Serializer()
	v.Serialize(ss)

	var msg Message
	msg.Headers.Set(MessageTypeHeader, StringValue(EventMessageType))
	msg.Headers.Set(EventTypeHeader, StringValue("initial-request"))
	if payload := ss.Bytes(); len(payload) > 0 {
		msg.Payload = payload
		msg.Headers.Set(ContentTypeHeader, StringValue(c.ContentType))
	}

	return c.enc().Encode(w, msg)
}

// DeserializeInitialResponse reads the first event stream message and
// deserializes it as the operation output.
func (c *Codec) DeserializeInitialResponse(schema *smithy.Schema, r io.Reader, out smithy.Deserializable) error {
	c.payloadBuf = c.payloadBuf[0:0]
	msg, err := c.dec().Decode(r, c.payloadBuf)
	if err != nil {
		return fmt.Errorf("decode initial response: %w", err)
	}

	eventType := msg.Headers.Get(EventTypeHeader)
	if eventType == nil || eventType.String() != "initial-response" {
		return fmt.Errorf("expected initial-response, got %v", eventType)
	}

	if len(msg.Payload) > 0 {
		sd := c.Codec.Deserializer(msg.Payload)
		if err := out.Deserialize(sd); err != nil {
			return fmt.Errorf("deserialize initial response: %w", err)
		}
	}

	return nil
}

// UnknownUnionMember is returned when a union member is returned over the
// wire, but has an unknown tag.
type UnknownUnionMember struct {
	Tag   string
	Value []byte
}

// Deserialize is a no-op. The raw bytes are already captured in Value.
func (*UnknownUnionMember) Deserialize(smithy.ShapeDeserializer) error {
	return nil
}

// UnknownMessageError provides an error when a message is received from the
// stream, but the reader is unable to determine what kind of message it is.
type UnknownMessageError struct {
	Type    string
	Message *Message
}

func (e *UnknownMessageError) Error() string {
	return "unknown event stream message type, " + e.Type
}

func deserializeError(msg *Message) error {
	code := "UnknownError"
	message := code
	if v := msg.Headers.Get(ErrorCodeHeader); v != nil {
		code = v.String()
	}
	if v := msg.Headers.Get(ErrorMessageHeader); v != nil {
		message = v.String()
	}
	return &smithy.GenericAPIError{
		Code:    code,
		Message: message,
	}
}

func isEOF(err error) bool {
	return err == io.EOF || err == io.ErrUnexpectedEOF
}
