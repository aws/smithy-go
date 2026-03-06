package restjson1

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"strings"

	"github.com/aws/smithy-go"
	awsjson "github.com/aws/smithy-go/aws-protocols/internal/json"
	httpbindingser "github.com/aws/smithy-go/aws-protocols/internal/httpbinding"
	httpbinding "github.com/aws/smithy-go/encoding/httpbinding"
	smithyio "github.com/aws/smithy-go/io"
	smithyhttp "github.com/aws/smithy-go/transport/http"
	"github.com/aws/smithy-go/traits"
)

// New returns an instance of the aws.protocols#restJson1 protocol.
func New() *Protocol {
	return &Protocol{}
}

// Protocol implements aws.protocols#restJson1.
type Protocol struct{}

var _ smithyhttp.ClientProtocol = (*Protocol)(nil)

// ID identifies the protocol.
func (*Protocol) ID() string {
	return "aws.protocols#restJson1"
}

// SerializeRequest serializes a request for restJson1.
func (p *Protocol) SerializeRequest(
	ctx context.Context,
	opSchema *smithy.Schema,
	in smithy.Serializable,
	req *smithyhttp.Request,
) error {
	// Resolve the HTTP method and URI from the operation's smithy.api#http trait.
	httpTrait, ok := smithy.SchemaTrait[*traits.HTTP](opSchema)
	if !ok {
		return fmt.Errorf("restjson1: operation schema missing smithy.api#http trait")
	}

	req.Method = httpTrait.Method
	path, query := httpbinding.SplitURI(httpTrait.URI)
	enc, err := httpbinding.NewEncoder(path, query, req.Header)
	if err != nil {
		return fmt.Errorf("restjson1: new encoder: %w", err)
	}

	body := awsjson.NewShapeSerializer()
	ser := &httpbindingser.Serializer{
		Request: req,
		Encoder: enc,
		Body:    body,
	}

	in.Serialize(ser)

	// Apply the encoder (URI labels, query, headers) to the request.
	built, err := enc.Encode(req.Request)
	if err != nil {
		return fmt.Errorf("restjson1: encode bindings: %w", err)
	}
	req.Request = built

	// Set the body. Streaming payload takes first precedence, then raw
	// payload (blob/string httpPayload), then the JSON body.
	if si, ok := in.(smithy.StreamingInput); ok && si.GetPayloadStream() != nil {
		stream := si.GetPayloadStream()
		contentType := ser.StreamingPayloadContentType
		if contentType == "" {
			contentType = "application/octet-stream"
		}
		req.Header.Set("Content-Type", contentType)
		sreq, err := req.SetStream(stream)
		if err != nil {
			return fmt.Errorf("restjson1: set stream: %w", err)
		}
		*req = *sreq
	} else {
		var payload []byte
		var contentType string
		if ser.PayloadBytes != nil {
			payload = ser.PayloadBytes
			contentType = ser.PayloadContentType
		} else {
			payload = body.Bytes()
			contentType = "application/json"
		}
		if len(payload) == 0 && ser.HasStructPayload {
			payload = []byte("{}")
			contentType = "application/json"
		}
		if len(payload) > 0 {
			if req.Header.Get("Content-Type") == "" {
				req.Header.Set("Content-Type", contentType)
			}
			sreq, err := req.SetStream(bytes.NewReader(payload))
			if err != nil {
				return fmt.Errorf("restjson1: set stream: %w", err)
			}
			*req = *sreq
		}
	}

	return nil
}

// DeserializeResponse deserializes a response for restJson1.
func (p *Protocol) DeserializeResponse(
	ctx context.Context,
	opSchema *smithy.Schema,
	types *smithy.TypeRegistry,
	resp *smithyhttp.Response,
	out smithy.Deserializable,
) error {
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return p.deserializeError(types, resp)
	}

	// For streaming output, set the body stream directly and deserialize
	// only HTTP-bound members (headers etc.), don't consume the body.
	if so, ok := out.(smithy.StreamingOutput); ok {
		so.SetPayloadStream(resp.Body)
		bodyDeser := awsjson.NewShapeDeserializer([]byte("{}"))
		deser := &httpbindingser.Deserializer{
			Response: resp.Response,
			Body:     bodyDeser,
		}
		if err := out.Deserialize(deser); err != nil {
			return &smithy.DeserializationError{Err: err}
		}
		return nil
	}

	payload, err := io.ReadAll(resp.Body)
	if err != nil {
		return &smithy.DeserializationError{Err: err}
	}

	var bodyDeser smithy.ShapeDeserializer
	if len(payload) > 0 {
		bodyDeser = awsjson.NewShapeDeserializer(payload)
	} else {
		bodyDeser = awsjson.NewShapeDeserializer([]byte("{}"))
	}

	deser := &httpbindingser.Deserializer{
		Response: resp.Response,
		Body:     bodyDeser,
		Payload:  payload,
	}
	if err := out.Deserialize(deser); err != nil {
		return &smithy.DeserializationError{Err: err}
	}

	return nil
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
	bodyInfo, err := getProtocolErrorInfo(decoder)
	if err != nil {
		var snapshot bytes.Buffer
		io.Copy(&snapshot, ringBuffer)
		return &smithy.DeserializationError{
			Err:      fmt.Errorf("failed to decode response body, %w", err),
			Snapshot: snapshot.Bytes(),
		}
	}

	errorBody.Seek(0, io.SeekStart)
	if typ, ok := resolveProtocolErrorType(headerCode, bodyInfo); ok {
		errorCode = typ
	}
	if len(bodyInfo.Message) != 0 {
		errorMessage = bodyInfo.Message
	}

	errorCode = sanitizeErrorCode(errorCode)

	perr, ok := types.DeserializableError(errorCode)
	if !ok {
		return &smithy.GenericAPIError{
			Code:    errorCode,
			Message: errorMessage,
		}
	}

	errorBody.Seek(0, io.SeekStart)
	errorBytes, _ := io.ReadAll(errorBody)

	var bodyDeser smithy.ShapeDeserializer
	if len(errorBytes) > 0 {
		bodyDeser = awsjson.NewShapeDeserializer(errorBytes)
	} else {
		bodyDeser = awsjson.NewShapeDeserializer([]byte("{}"))
	}

	// Use the HTTP binding deserializer so error shapes with httpHeader
	// traits are deserialized from response headers.
	deser := &httpbindingser.Deserializer{
		Response: response.Response,
		Body:     bodyDeser,
		Payload:  errorBytes,
	}
	if err := perr.Deserialize(deser); err != nil {
		return &smithy.DeserializationError{Err: err}
	}

	return perr
}

type protocolErrorInfo struct {
	Type    string `json:"__type"`
	Message string
	Code    any
}

func getProtocolErrorInfo(decoder *json.Decoder) (protocolErrorInfo, error) {
	var errInfo protocolErrorInfo
	if err := decoder.Decode(&errInfo); err != nil {
		if err == io.EOF {
			return errInfo, nil
		}
		return errInfo, err
	}
	return errInfo, nil
}

func resolveProtocolErrorType(headerType string, bodyInfo protocolErrorInfo) (string, bool) {
	if len(headerType) != 0 {
		return headerType, true
	} else if len(bodyInfo.Type) != 0 {
		return bodyInfo.Type, true
	} else if code, ok := bodyInfo.Code.(string); ok && len(code) != 0 {
		return code, true
	}
	return "", false
}

func sanitizeErrorCode(code string) string {
	if idx := strings.Index(code, ":"); idx != -1 {
		code = code[:idx]
	}
	if idx := strings.Index(code, "#"); idx != -1 {
		code = code[idx+1:]
	}
	return code
}
