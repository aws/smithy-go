package apikey

import (
	"context"
	"fmt"

	"github.com/aws/smithy-go/auth"
	"github.com/aws/smithy-go/middleware"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// Signer provides an interface for implementations to decorate a request
// message with an api key. The signer is responsible for validating the
// message type is compatible with the signer.
type Signer interface {
	SignWithApiKey(context.Context, string, auth.Message) (auth.Message, error)
}

// AuthenticationMiddleware provides the Finalize middleware step for signing
// an request message with a api key.
type AuthenticationMiddleware struct {
	signer         Signer
	apiKeyProvider ApiKeyProvider
	authDefinition auth.HttpAuthDefinition
}

// AddAuthenticationMiddleware helper adds the AuthenticationMiddleware to the
// middleware Stack in the Finalize step with the options provided.
func AddAuthenticationMiddleware(s *middleware.Stack, signer Signer, apiKeyProvider ApiKeyProvider, authDefinition auth.HttpAuthDefinition) error {
	return s.Finalize.Add(
		NewAuthenticationMiddleware(signer, apiKeyProvider, authDefinition),
		middleware.After,
	)
}

// NewAuthenticationMiddleware returns an initialized AuthenticationMiddleware.
func NewAuthenticationMiddleware(signer Signer, apiKeyProvider ApiKeyProvider, authDefinition auth.HttpAuthDefinition) *AuthenticationMiddleware {
	return &AuthenticationMiddleware{
		signer:         signer,
		apiKeyProvider: apiKeyProvider,
		authDefinition: authDefinition,
	}
}

const authenticationMiddlewareID = "ApiKeyAuthentication"

// ID returns the resolver identifier
func (m *AuthenticationMiddleware) ID() string {
	return authenticationMiddlewareID
}

// HandleFinalize implements the FinalizeMiddleware interface in order to
// update the request with api key authentication.
func (m *AuthenticationMiddleware) HandleFinalize(
	ctx context.Context, in middleware.FinalizeInput, next middleware.FinalizeHandler,
) (
	out middleware.FinalizeOutput, metadata middleware.Metadata, err error,
) {
	if m.apiKeyProvider == nil || ctx.Value(auth.CURRENT_AUTH_CONFIG) != nil {
		return next.HandleFinalize(ctx, in)
	}
	apiKey, err := m.apiKeyProvider.RetrieveApiKey(ctx)
	if err != nil || len(apiKey) == 0 {
		return next.HandleFinalize(ctx, in)
	}

	ctx = context.WithValue(ctx, auth.CURRENT_AUTH_CONFIG, m.authDefinition)
	signedMessage, err := m.signer.SignWithApiKey(ctx, apiKey, in.Request)
	if err != nil {
		ctx = context.WithValue(ctx, auth.CURRENT_AUTH_CONFIG, nil)
		return next.HandleFinalize(ctx, in)
	}

	in.Request = signedMessage
	return next.HandleFinalize(ctx, in)
}

// SignHTTPSMessage provides an api key authentication implementation that
// will sign the message with the provided api key.
//
// Will fail if the message is not a smithy-go HTTP request or the request is
// not HTTPS.
type SignHTTPSMessage struct{}

// NewSignHTTPSMessage returns an initialized signer for HTTP messages.
func NewSignHTTPSMessage() *SignHTTPSMessage {
	return &SignHTTPSMessage{}
}

// SignWithApiKey returns a copy of the HTTP request with the api key
// added via either Header or Query parameter as defined in the Smithy model.
//
// Returns an error if the request's URL scheme is not HTTPS, or the request
// message is not an smithy-go HTTP Request pointer type.
func (SignHTTPSMessage) SignWithApiKey(ctx context.Context, apiKey string, message auth.Message) (auth.Message, error) {
	req, ok := message.(*smithyhttp.Request)
	if !ok {
		return nil, fmt.Errorf("expect smithy-go HTTP Request, got %T", message)
	}

	if !req.IsHTTPS() {
		return nil, fmt.Errorf("api key with HTTP request requires HTTPS")
	}

	reqClone := req.Clone()
	authDefinition := ctx.Value(auth.CURRENT_AUTH_CONFIG).(auth.HttpAuthDefinition)
	if authDefinition.In == "header" {
		reqClone.Header.Set(authDefinition.Name, authDefinition.Scheme+" "+apiKey)
	} else if authDefinition.In == "query" {
		values := reqClone.URL.Query()
		values.Set(authDefinition.Name, apiKey)
		reqClone.URL.RawQuery = values.Encode()
	}

	return reqClone, nil
}
