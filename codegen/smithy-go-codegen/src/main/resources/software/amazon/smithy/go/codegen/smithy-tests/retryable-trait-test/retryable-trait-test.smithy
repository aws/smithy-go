$version: "2.0"

namespace smithy.example

use smithy.protocols#rpcv2Cbor

@rpcv2Cbor
service Example {
    version: "1.0.0"
    operations: [
        GetThing
    ]
}

operation GetThing {
    input := {}
    output := {
        /// Not escaped: RetryableTrait is only reserved on error shapes.
        retryableTrait: String
    }
    errors: [
        RetryableConflict
        ThrottledRequest
        NotRetryable
    ]
}

/// Retryable, but not a throttling error.
@error("client")
@retryable
@httpError(409)
structure RetryableConflict {
    message: String
}

/// Retryable throttling error.
@error("client")
@retryable(throttling: true)
@httpError(429)
structure ThrottledRequest {
    message: String
}

/// Not retryable.
@error("client")
@httpError(400)
structure NotRetryable {
    message: String

    /// Escaped to avoid colliding with the generated RetryableTrait method.
    retryableTrait: String
}
