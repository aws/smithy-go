package http

import smithy "github.com/aws/smithy-go"

var (
	sigV4SigningNameKey       struct{}
	sigV4SigningRegionKey     struct{}
	sigV4IsUnsignedPayloadKey struct{}
	sigV4ASigningNameKey      struct{}
	sigV4ASigningRegionsKey   struct{}
)

// GetSigV4SigningName gets the signing name from Properties.
func GetSigV4SigningName(p *smithy.Properties) (string, bool) {
	v, ok := p.Get(sigV4SigningNameKey).(string)
	return v, ok
}

// SetSigV4SigningName sets the signing name on Properties.
func SetSigV4SigningName(p *smithy.Properties, name string) {
	p.Set(sigV4SigningNameKey, name)
}

// GetSigV4SigningRegion gets the signing region from Properties.
func GetSigV4SigningRegion(p *smithy.Properties) (string, bool) {
	v, ok := p.Get(sigV4SigningRegionKey).(string)
	return v, ok
}

// SetSigV4SigningRegion sets the signing region on Properties.
func SetSigV4SigningRegion(p *smithy.Properties, region string) {
	p.Set(sigV4SigningRegionKey, region)
}

// GetSigV4IsUnsignedPayload gets whether the payload is unsigned from Properties.
func GetSigV4IsUnsignedPayload(p *smithy.Properties) (bool, bool) {
	v, ok := p.Get(sigV4IsUnsignedPayloadKey).(bool)
	return v, ok
}

// SetSigV4IsUnsignedPayload sets whether the payload is unsigned on Properties.
func SetSigV4IsUnsignedPayload(p *smithy.Properties, isUnsignedPayload bool) {
	p.Set(sigV4IsUnsignedPayloadKey, isUnsignedPayload)
}

// GetSigV4ASigningName gets the v4a signing name from Properties.
func GetSigV4ASigningName(p *smithy.Properties) (string, bool) {
	v, ok := p.Get(sigV4ASigningNameKey).(string)
	return v, ok
}

// SetSigV4ASigningName sets the signing name on Properties.
func SetSigV4ASigningName(p *smithy.Properties, name string) {
	p.Set(sigV4ASigningNameKey, name)
}

// GetSigV4ASigningRegion gets the v4a signing region set from Properties.
func GetSigV4ASigningRegions(p *smithy.Properties) ([]string, bool) {
	v, ok := p.Get(sigV4ASigningRegionsKey).([]string)
	return v, ok
}

// SetSigV4ASigningRegion sets the v4a signing region set on Properties.
func SetSigV4ASigningRegion(p *smithy.Properties, regions []string) {
	p.Set(sigV4ASigningRegionsKey, regions)
}
