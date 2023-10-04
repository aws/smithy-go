package http

import (
	smithy "github.com/aws/smithy-go"
	"github.com/aws/smithy-go/auth"
)

// NewSigV4Option creates a SigV4 auth Option from an input configuration.
func NewSigV4Option(propFns ...func(*SigV4Properties)) *auth.Option {
	var props SigV4Properties
	for _, f := range propFns {
		f(&props)
	}

	return &auth.Option{
		SchemeID:         SchemeIDSigV4,
		SignerProperties: props.toSignerProperties(),
	}
}

// SigV4Properties represent the inputs to the SigV4 auth scheme.
type SigV4Properties struct {
	SigningName       string
	SigningRegion     string
	IsUnsignedPayload bool
}

func (p *SigV4Properties) toSignerProperties() smithy.Properties {
	var props smithy.Properties
	SetSigV4SigningName(&props, p.SigningName)
	SetSigV4SigningRegion(&props, p.SigningRegion)
	SetIsUnsignedPayload(&props, p.IsUnsignedPayload)
	return props
}

// NewSigV4AOption creates a SigV4A auth Option from an input configuration.
func NewSigV4AOption(propFns ...func(*SigV4AProperties)) *auth.Option {
	var props SigV4AProperties
	for _, f := range propFns {
		f(&props)
	}

	return &auth.Option{
		SchemeID:         SchemeIDSigV4A,
		SignerProperties: props.toSignerProperties(),
	}
}

// SigV4AProperties represent the inputs to the SigV4A auth scheme.
type SigV4AProperties struct {
	SigningName       string
	SigningRegions    []string
	IsUnsignedPayload bool
}

func (p *SigV4AProperties) toSignerProperties() smithy.Properties {
	var props smithy.Properties
	SetSigV4ASigningName(&props, p.SigningName)
	SetSigV4ASigningRegions(&props, p.SigningRegions)
	SetIsUnsignedPayload(&props, p.IsUnsignedPayload)
	return props
}

// NewBearerOption creates a Bearer auth Option.
//
// The Bearer auth scheme currently has no configuration, so the inputs to this
// API will be ignored.
func NewBearerOption(propFns ...func(*BearerProperties)) *auth.Option {
	return &auth.Option{SchemeID: SchemeIDBearer}
}

// BearerProperties represents a configuration of the Bearer auth scheme.
type BearerProperties struct{}

// NewAnonymousOption creates an Anonymous auth Option.
//
// The Anonymous auth scheme currently has no configuration, so the inputs to
// this API will be ignored.
func NewAnonymousOption(propFns ...func(*AnonymousProperties)) *auth.Option {
	return &auth.Option{SchemeID: SchemeIDAnonymous}
}

// AnonymousProperties represents a configuration of the Anonymous auth scheme.
type AnonymousProperties struct{}
