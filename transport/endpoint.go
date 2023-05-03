package transport

import (
	"net/http"
	"net/url"
	"github.com/aws/smithy-go"
)

// Endpoint is the endpoint object returned
// by Endpoint resolution V2
type Endpoint struct {
	URI url.URL

	Headers *http.Header

	Properties smithy.Properties
}
