package apikey

import (
	"context"
	"fmt"

	"github.com/aws/smithy-go/auth"
	"github.com/aws/smithy-go/middleware"
	smithyhttp "github.com/aws/smithy-go/transport/http"
)

// Message is the middleware stack's request transport message value.
type Message interface{}

// Signer provides an interface for implementations to decorate a request
// message with an api key. The signer is responsible for validating the
// message type is compatible with the signer.
type Signer interface {
	SignWithApiKey(context.Context, string, *auth.HttpAuthDefinition, Message) (Message, error)
}

// AuthenticationMiddleware provides the Finalize middleware step for signing
// a request message with an api key.
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
		fmt.Println("failed AuthenticationMiddleware wrap message, %w", err)
		return next.HandleFinalize(ctx, in)
	}

	signedMessage, err := m.signer.SignWithApiKey(ctx, apiKey, &m.authDefinition, in.Request)
	if err != nil {
		fmt.Println("failed AuthenticationMiddleware sign message, %w", err)
		return next.HandleFinalize(ctx, in)
	}

	in.Request = signedMessage
	return next.HandleFinalize(context.WithValue(ctx, auth.CURRENT_AUTH_CONFIG, m.authDefinition), in)
}

// SignMessage provides an api key authentication implementation that
// will sign the message with the provided api key.
type SignMessage struct{}

// NewSignMessage returns an initialized signer for HTTP messages.
func NewSignMessage() *SignMessage {
	return &SignMessage{}
}

// SignWithApiKey returns a copy of the HTTP request with the api key
// added via either Header or Query parameter as defined in the Smithy model.
//
// Returns an error if the request message is not an smithy-go HTTP Request pointer type.
func (SignMessage) SignWithApiKey(ctx context.Context, apiKey string, authDefinition *auth.HttpAuthDefinition, message Message) (Message, error) {
	req, ok := message.(*smithyhttp.Request)
	if !ok {
		return nil, fmt.Errorf("expect smithy-go HTTP Request, got %T", message)
	}

	if authDefinition == nil || (authDefinition.In != "header" && authDefinition.In != "query") {
		return nil, fmt.Errorf("invalid HTTP auth definition")
	}

	reqClone := req.Clone()
	if authDefinition.In == "header" {
		reqClone.Header.Set(authDefinition.Name, authDefinition.Scheme+" "+apiKey)
	} else if authDefinition.In == "query" {
		values := reqClone.URL.Query()
		values.Set(authDefinition.Name, apiKey)
		reqClone.URL.RawQuery = values.Encode()
	}

	return reqClone, nil
}
