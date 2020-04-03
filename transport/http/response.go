package http

import "net/http"

// Response provides the HTTP specific response structure for HTTP specific
// middleware steps to use to deserialize the response from an operation call.
type Response struct {
	*http.Response
}
