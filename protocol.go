package smithy

import "context"

// ClientProtocol defines the interface through which client-side operation
// request/responses are (de)serialized across the wire.
//
// While a caller CAN define their own protocol, it is almost never necessary
// to do so. In practice, a generated client will utilize one of the predefined
// protocols implemented as part of the Smithy client runtime.
type ClientProtocol interface {
	ID() string
	SerializeRequest(context.Context, Schema, Serializable, any) error
	DeserializeResponse(context.Context, Schema, any, Deserializable) error
}

// Server defines the interface through which server-side operation
// request/responses are (de)serialized across the wire.
//
// While a caller CAN define their own protocol, it is almost never necessary
// to do so. In practice, a generated client will utilize one of the predefined
// protocols implemented as part of the Smithy client runtime.
type ServerProtocol interface {
	ID() string
	DeserializeRequest(ctx context.Context, operation Schema, request any) (input any, err error)
	SerializeResponse(ctx context.Context, operation Schema, output, response any) (err error)
}
