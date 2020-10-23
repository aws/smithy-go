package id

const (
	// ComputeContentLength is the slot ID for middleware that determines the transport body's content length
	ComputeContentLength = "ComputeContentLength"
	// ContentChecksum is the slot ID for middleware that handles the httpChecksumRequired trait.
	ContentChecksum = "ContentChecksum"
	// CloseResponseBody is the slot ID for middleware that handles closing the transport layer response body.
	CloseResponseBody = "CloseResponseBody"
	// ErrorCloseResponseBody is the slot ID for middleware that handles closing the transport layer response body if an error occurred.
	ErrorCloseResponseBody = "ErrorCloseResponseBody"
	// OperationDeserializer is the slot ID for middleware that handles deserialization of an operation response.
	OperationDeserializer = "OperationDeserializer"
	// OperationIdempotencyTokenAutoFill is the slot ID for middleware that auto-fills members marked with idempotencyToken trait.
	OperationIdempotencyTokenAutoFill = "OperationIdempotencyTokenAutoFill"
	// OperationInputValidation is the slot ID for middleware that handles validation traits.
	OperationInputValidation = "OperationInputValidation"
	// OperationSerializer is the slot ID for middleware that handles the serialization of operation requests.
	OperationSerializer = "OperationSerializer"
	// ValidateContentLength is the slot ID for middleware that handles ensuring content-length has been set.
	ValidateContentLength = "ValidateContentLength"
)
