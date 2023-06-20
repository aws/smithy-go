package http

import (
	"strings"
)

var validChars = map[rune]bool{
	'!': true, '#': true, '$': true, '%': true, '&': true, '\'': true, '*': true, '+': true,
	'-': true, '.': true, '^': true, '_': true, '`': true, '|': true, '~': true,
}

// UserAgentBuilder is a builder for a HTTP User-Agent string.
type UserAgentBuilder struct {
	sb strings.Builder
}

// NewUserAgentBuilder returns a new UserAgentBuilder.
func NewUserAgentBuilder() *UserAgentBuilder {
	return &UserAgentBuilder{sb: strings.Builder{}}
}

// AddKey adds the named component/product to the agent string
func (u *UserAgentBuilder) AddKey(key string) {
	u.appendTo(key)
}

// AddKeyValue adds the named key to the agent string with the given value.
func (u *UserAgentBuilder) AddKeyValue(key, value string) {
	u.appendTo(key + "#" + strings.Map(rules, value))
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

func rules(r rune) rune {
	switch {
	case r >= '0' && r <= '9':
		return r
	case r >= 'A' && r <= 'Z' || r >= 'a' && r <= 'z':
		return r
	case validChars[r]:
		return r
	default:
		return '-'
	}
}
