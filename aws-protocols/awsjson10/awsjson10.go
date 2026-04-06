package awsjson10

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"

	"github.com/aws/smithy-go"
	awsjson "github.com/aws/smithy-go/aws-protocols/internal/json"
	"github.com/aws/smithy-go/eventstream"
	smithyio "github.com/aws/smithy-go/io"
	"github.com/aws/smithy-go/middleware"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// New returns an instance of the awsJson 1.0 protocol.
func New() *Protocol {
	return &Protocol{
		version: "1.0",
		Codec: &eventstream.Codec{
			Codec:       &awsjson.Codec{},
			ContentType: "application/json",
		},
	}
}

// New11 returns an instance of the awsJson 1.1 protocol.
//
// TODO(serde2): figure out how we want to organize awsjson10 vs 11
func New11() *Protocol {
	return &Protocol{
		version: "1.1",
		Codec: &eventstream.Codec{
			Codec:       &awsjson.Codec{},
			ContentType: "application/json",
		},
	}
}

// Protocol implements aws.protocols#awsJson10.
type Protocol struct {
	version            string
	UseQueryCompatible bool

	*eventstream.Codec
}

var _ smithyhttp.ClientProtocol = (*Protocol)(nil)

// ID identifies the protocol.
func (p *Protocol) ID() string {
	if p.version == "1.1" {
		return "aws.protocols#awsJson1_1"
	}
	return "aws.protocols#awsJson1_0"
}

// SerializeRequest serializes a request for AWS Json 1.0.
func (p *Protocol) SerializeRequest(
	ctx context.Context,
	schema *smithy.OperationSchema,
	in smithy.Serializable,
	req *smithyhttp.Request,
) error {
	req.Method = http.MethodPost
	req.Header.Set("X-Amz-Target", fmt.Sprintf("%s.%s", middleware.GetServiceName(ctx), middleware.GetOperationName(ctx)))
	if schema.IsInputEventStream() {
		req.Header.Set("Content-Type", "application/vnd.amazon.eventstream")
		return nil
	}

	req.Header.Set("Content-Type", "application/x-amz-json-"+p.version)
	if p.UseQueryCompatible {
		req.Header.Set("X-Amzn-Query-Compatible", "true")
	}

	ss := awsjson.NewShapeSerializer()
	in.Serialize(ss)

	sreq, err := req.SetStream(bytes.NewReader(ss.Bytes()))
	if err != nil {
		return fmt.Errorf("set stream: %w", err)
	}

	*req = *sreq
	return nil
}

// DeserializeResponse deserializes a response for AWS Json 1.0.
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

	sd := awsjson.NewShapeDeserializer(payload)
	if err := out.Deserialize(sd); err != nil {
		return &smithy.DeserializationError{Err: err}
	}

	return nil
}

// HasInitialEventMessage is true because this is an RPC protocol.
func (*Protocol) HasInitialEventMessage() bool {
	return true
}

// this handles both awsJson 1.0 and 1.1 - the only thing that 1.1 adds is
// error shape renaming (basically not having the namespace) but both versions
// of the protocol are supposed to support this anyway so it doesn't matter
func (p *Protocol) deserializeError(types *smithy.TypeRegistry, response *smithyhttp.Response) error {
	var errorBuffer bytes.Buffer
	if _, err := io.Copy(&errorBuffer, response.Body); err != nil {
		return &smithy.DeserializationError{Err: fmt.Errorf("failed to copy error response body, %w", err)}
	}
	errorBody := bytes.NewReader(errorBuffer.Bytes())

	errorCode := "UnknownError"
	errorMessage := errorCode

	var headerCode string
	headerCode = response.Header.Get("X-Amzn-ErrorType")

	var buff [1024]byte
	ringBuffer := smithyio.NewRingBuffer(buff[:])

	body := io.TeeReader(errorBody, ringBuffer)
	decoder := json.NewDecoder(body)
	decoder.UseNumber()
	bodyInfo, err := awsjson.GetProtocolErrorInfo(decoder)
	if err != nil {
		var snapshot bytes.Buffer
		io.Copy(&snapshot, ringBuffer)
		err = &smithy.DeserializationError{
			Err:      fmt.Errorf("failed to decode response body, %w", err),
			Snapshot: snapshot.Bytes(),
		}
		return err
	}

	errorBody.Seek(0, io.SeekStart)
	if typ, ok := awsjson.ResolveProtocolErrorType(headerCode, bodyInfo); ok {
		errorCode = typ
	}
	if len(bodyInfo.Message) != 0 {
		errorMessage = bodyInfo.Message
	}

	errorCode = awsjson.SanitizeErrorCode(errorCode)

	perr, ok := types.DeserializableError(errorCode)
	if !ok {
		return &smithy.GenericAPIError{
			Code:    errorCode,
			Message: errorMessage,
		}

	}

	errorBody.Seek(0, io.SeekStart)
	errorBytes, _ := io.ReadAll(errorBody)
	if len(errorBytes) > 0 {
		deser := awsjson.NewShapeDeserializer(errorBytes)
		if err := perr.Deserialize(deser); err != nil {
			return &smithy.DeserializationError{Err: err}
		}
	}

	return perr
}
