package traits

// HTTPHeader represents smithy.api#httpHeader.
type HTTPHeader struct {
	Name string
}

// TraitID identifies the trait.
func (*HTTPHeader) TraitID() string { return "smithy.api#httpHeader" }

// HTTPLabel represents smithy.api#httpLabel.
type HTTPLabel struct{}

// TraitID identifies the trait.
func (*HTTPLabel) TraitID() string { return "smithy.api#httpLabel" }

// HTTPPayload represents smithy.api#httpPayload.
type HTTPPayload struct{}

// TraitID identifies the trait.
func (*HTTPPayload) TraitID() string { return "smithy.api#httpPayload" }

// HTTPPrefixHeaders represents smithy.api#httpPrefixHeaders.
type HTTPPrefixHeaders struct {
	Prefix string
}

// TraitID identifies the trait.
func (*HTTPPrefixHeaders) TraitID() string { return "smithy.api#httpPrefixHeaders" }

// HTTPQuery represents smithy.api#httpQuery.
type HTTPQuery struct {
	Name string
}

// TraitID identifies the trait.
func (*HTTPQuery) TraitID() string { return "smithy.api#httpQuery" }

// HTTPQueryParams represents smithy.api#httpQueryParams.
type HTTPQueryParams struct{}

// TraitID identifies the trait.
func (*HTTPQueryParams) TraitID() string { return "smithy.api#httpQueryParams" }

// HTTPResponseCode represents smithy.api#httpResponseCode.
type HTTPResponseCode struct{}

// TraitID identifies the trait.
func (*HTTPResponseCode) TraitID() string { return "smithy.api#httpResponseCode" }

// HTTP represents smithy.api#http.
type HTTP struct {
	Method string
	URI    string
	Code   int
}

// TraitID identifies the trait.
func (*HTTP) TraitID() string { return "smithy.api#http" }

// HTTPError represents smithy.api#httpError.
type HTTPError struct {
	Code int
}

// TraitID identifies the trait.
func (*HTTPError) TraitID() string { return "smithy.api#httpError" }
