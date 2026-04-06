package restjson1

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"

	"github.com/aws/smithy-go"
	internalhttpbinding "github.com/aws/smithy-go/aws-protocols/internal/httpbinding"
	internaljson "github.com/aws/smithy-go/aws-protocols/internal/json"
	"github.com/aws/smithy-go/eventstream"
	smithyio "github.com/aws/smithy-go/io"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// New returns an instance of the aws.protocols#restJson1 protocol.
func New() *Protocol {
	return &Protocol{
		Codec: &eventstream.Codec{
			Codec:       &internaljson.Codec{},
			ContentType: "application/json",
		},
	}
}

// Protocol implements aws.protocols#restJson1.
type Protocol struct {
	*eventstream.Codec
}

var _ smithyhttp.ClientProtocol = (*Protocol)(nil)

// ID identifies the protocol.
func (*Protocol) ID() string {
	return "aws.protocols#restJson1"
}

// SerializeRequest serializes a request for restJson1.
func (p *Protocol) SerializeRequest(
	ctx context.Context,
	op *smithy.OperationSchema,
	in smithy.Serializable,
	req *smithyhttp.Request,
) error {
	serializer, err := internalhttpbinding.NewShapeSerializer(op.Schema, req, internaljson.NewShapeSerializer(sUseJSONName))
	if err != nil {
		return err
	}

	in.Serialize(serializer)

	contentType := "application/json"
	if op.IsInputEventStream() {
		contentType = "application/vnd.amazon.eventstream"
	}
	if err := serializer.Build(in, contentType); err != nil {
		return fmt.Errorf("build request: %w", err)
	}

	return nil
}

// DeserializeResponse deserializes a response for restJson1.
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

	// @httpPayload + @streaming = do not touch the body at all, it's the
	// caller's problem
	if so, ok := out.(smithy.StreamingOutput); ok {
		so.SetPayloadStream(resp.Body)
		deser := internalhttpbinding.NewShapeDeserializer(resp.Response, bd(nil), nil)
		if err := out.Deserialize(deser); err != nil {
			return &smithy.DeserializationError{Err: err}
		}
		return nil
	}

	if op.IsOutputEventStream() {
		deser := internalhttpbinding.NewShapeDeserializer(resp.Response, bd(nil), nil)
		if err := out.Deserialize(deser); err != nil {
			return &smithy.DeserializationError{Err: err}
		}
		return nil
	}

	payload, err := io.ReadAll(resp.Body)
	if err != nil {
		return &smithy.DeserializationError{Err: err}
	}

	deser := internalhttpbinding.NewShapeDeserializer(resp.Response, bd(payload), payload)
	if err := out.Deserialize(deser); err != nil {
		return &smithy.DeserializationError{Err: err}
	}

	return nil
}

// HasInitialEventMessage is false for REST-style protocols with HTTP bindings.
func (*Protocol) HasInitialEventMessage() bool {
	return false
}

func (p *Protocol) deserializeError(types *smithy.TypeRegistry, response *smithyhttp.Response) error {
	var errorBuffer bytes.Buffer
	if _, err := io.Copy(&errorBuffer, response.Body); err != nil {
		return &smithy.DeserializationError{Err: fmt.Errorf("failed to copy error response body, %w", err)}
	}
	errorBody := bytes.NewReader(errorBuffer.Bytes())

	errorCode := "UnknownError"
	errorMessage := errorCode

	headerCode := response.Header.Get("X-Amzn-ErrorType")

	var buff [1024]byte
	ringBuffer := smithyio.NewRingBuffer(buff[:])

	body := io.TeeReader(errorBody, ringBuffer)
	decoder := json.NewDecoder(body)
	decoder.UseNumber()
	bodyInfo, err := internaljson.GetProtocolErrorInfo(decoder)
	if err != nil {
		var snapshot bytes.Buffer
		io.Copy(&snapshot, ringBuffer)
		return &smithy.DeserializationError{
			Err:      fmt.Errorf("failed to decode response body, %w", err),
			Snapshot: snapshot.Bytes(),
		}
	}

	errorBody.Seek(0, io.SeekStart)
	if typ, ok := internaljson.ResolveProtocolErrorType(headerCode, bodyInfo); ok {
		errorCode = typ
	}
	if len(bodyInfo.Message) != 0 {
		errorMessage = bodyInfo.Message
	}

	errorCode = internaljson.SanitizeErrorCode(errorCode)

	perr, ok := types.DeserializableError(errorCode)
	if !ok {
		return &smithy.GenericAPIError{
			Code:    errorCode,
			Message: errorMessage,
		}
	}

	errorBody.Seek(0, io.SeekStart)
	errorBytes, _ := io.ReadAll(errorBody)

	deser := internalhttpbinding.NewShapeDeserializer(response.Response, bd(errorBytes), errorBytes)
	if err := perr.Deserialize(deser); err != nil {
		return &smithy.DeserializationError{Err: err}
	}

	return perr
}

func bd(payload []byte) smithy.ShapeDeserializer {
	if len(payload) == 0 {
		payload = []byte("{}")
	}
	return internaljson.NewShapeDeserializer(payload, dUseJSONName)
}

// TODO(serde2): can we just have one struct?
func sUseJSONName(o *internaljson.ShapeSerializerOptions) {
	o.UseJSONName = true
}

func dUseJSONName(o *internaljson.ShapeDeserializerOptions) {
	o.UseJSONName = true
}
