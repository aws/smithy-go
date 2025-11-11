package middleware

import (
	"context"
	"fmt"

	"github.com/aws/smithy-go"
)

// SerializeRequestMiddleware serializes an operation's input request according
// to its transport protocol.
type SerializeRequestMiddleware struct {
	Protocol  smithy.ClientProtocol
	Operation smithy.Schema
}

var _ SerializeMiddleware = (*SerializeRequestMiddleware)(nil)

// ID identifies the middleware.
func (*SerializeRequestMiddleware) ID() string {
	return "OperationSerializer"
}

// HandleSerialize serializes the input request.
func (m *SerializeRequestMiddleware) HandleSerialize(
	ctx context.Context, in SerializeInput, next SerializeHandler,
) (
	out SerializeOutput, md Metadata, err error,
) {
	input, ok := in.Parameters.(smithy.Serializable)
	if !ok {
		return out, md, fmt.Errorf("input is not Serializable: %T")
	}

	err = m.Protocol.SerializeRequest(ctx, m.Operation, input, in.Request)
	if err != nil {
		return out, md, fmt.Errorf("serialize input: %w", err)
	}

	return next.HandleSerialize(ctx, in)
}

// SerializeRequestMiddleware serializes an operation's input request according
// to its transport protocol.
type DeserializeRequestMiddleware struct {
	Protocol  smithy.ClientProtocol
	Operation smithy.Schema
}

var _ DeserializeMiddleware = (*DeserializeRequestMiddleware)(nil)

// ID identifies the middleware.
func (*DeserializeRequestMiddleware) ID() string {
	return "OperationDeserializer"
}

// HandleSerialize serializes the input request.
func (m *DeserializeRequestMiddleware) HandleDeserialize(
	ctx context.Context, in DeserializeInput, next DeserializeHandler,
) (
	out DeserializeOutput, md Metadata, err error,
) {
	out, md, err = next.HandleDeserialize(ctx, in)
	return out, md, err
}
