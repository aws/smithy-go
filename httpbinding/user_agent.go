package httpbinding

import (
	"fmt"
	"strings"
)

// UserAgentBuilder is a builder for a HTTP User-Agent string.
type UserAgentBuilder struct {
	sb *strings.Builder
}

// NewUserAgentBuilder returns a new UserAgentBuilder.
func NewUserAgentBuilder() *UserAgentBuilder {
	return &UserAgentBuilder{sb: &strings.Builder{}}
}

// AddComponent adds the named component/product to the agent string
func (u *UserAgentBuilder) AddComponent(name string) {
	u.appendTo(name)
}

// AddVersionedComponent adds the named component/product to the agent string with the annotated version.
func (u *UserAgentBuilder) AddVersionedComponent(name, version string) {
	u.appendTo(fmt.Sprintf("%s/%s", name, version))
}

// Build returns the constructed User-Agent string. May be called multiple times.
func (u *UserAgentBuilder) Build() string {
	return u.sb.String()
}

func (u *UserAgentBuilder) appendTo(value string) {
	if u.sb.Len() > 0 {
		u.sb.WriteRune(' ')
	}
	u.sb.WriteString(value)
}
