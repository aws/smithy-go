// Package sigv4a provides the generic smithy-go client wiring for AWS
// Signature Version 4a (asymmetric) request signing.
//
// It adapts the standalone signer in
// [github.com/aws/smithy-go/aws-http-auth/sigv4a] to the client auth scheme
// interfaces in [github.com/aws/smithy-go/transport/http], allowing non-SDK
// smithy-go clients to sign requests with SigV4A without depending on the AWS
// SDK for Go. It is a sibling package to
// [github.com/aws/smithy-go/aws-http-auth-schemes/sigv4] within the same
// module, so that the core smithy-go runtime does not take on an
// AWS-specific dependency.
package sigv4a
