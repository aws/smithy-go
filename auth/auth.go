package auth

type key string

const (
	CURRENT_AUTH_CONFIG key = "currentAuthConfig"
)

type HttpAuthDefinition struct {
	In     string
	Name   string
	Scheme string
}

// Message is the middleware stack's request transport message value.
type Message interface{}
