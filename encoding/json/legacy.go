package json

import (
	internaljson "github.com/aws/smithy-go/internal/encoding/json"
)

// Deprecated: This API is part of the legacy JSON encoding surface. Use
// [Codec] instead.
type Value = internaljson.Value

// Deprecated: This API is part of the legacy JSON encoding surface. Use
// [Codec] instead.
type Object = internaljson.Object

// Deprecated: This API is part of the legacy JSON encoding surface. Use
// [Codec] instead.
type Array = internaljson.Array

// Deprecated: This API is part of the legacy JSON encoding surface. Use
// [Codec] instead.
type Encoder = internaljson.Encoder

// Deprecated: This API is part of the legacy JSON encoding surface. Use
// [Codec] instead.
func NewEncoder() *Encoder {
	return internaljson.NewEncoder()
}
