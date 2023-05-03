package transport

import (
	"net/http"
	"net/url"
	"github.com/aws/smithy-go"
)

// Endpoint is a Smithy endpoint.
type Endpoint struct {
	URI url.URL

	Headers *http.Header

	Properties smithy.Properties
}
