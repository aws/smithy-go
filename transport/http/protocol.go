package http

import (
	"context"

	"github.com/aws/smithy-go"
)

// ClientProtocol defines the interface through which client-side operation
// request/responses are (de)serialized across the wire.
//
// While a caller CAN define their own protocol, it is almost never necessary
// to do so. In practice, a generated client will utilize one of the predefined
// protocols implemented as part of the Smithy client runtime.
type ClientProtocol interface {
	ID() string
	SerializeRequest(context.Context, smithy.Serializable, *Request) error
	DeserializeResponse(ctx context.Context, types *smithy.TypeRegistry, resp *Response, out smithy.Deserializable) error
}
