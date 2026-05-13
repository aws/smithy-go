package json

import (
	"fmt"
	"io"
)

// scanner scans json tokens without the allocation overhead of json.Token.
// It does not care about the syntactic validity of the json at all, that is
// handled by [parser].
//
// Largely inspired by https://dave.cheney.net/paste/gophercon-sg-2023.html
// although this version operates on a fully buffered input since we are
// generally dealing with smaller RPC-style payloads.
type scanner struct {
	p []byte
	i int
}

func (s *scanner) Next() ([]byte, error) {
	for ; s.i < len(s.p); s.i++ {
		c := s.p[s.i]
		if whitespace[c] {
			continue
		}

		switch c {
		case '{', '}', '[', ']', ':', ',':
			s.i++
			return s.p[s.i-1 : s.i], nil
		case '"':
			return s.scanString()
		case 't':
			return s.scanLiteral("true")
		case 'f':
			return s.scanLiteral("false")
		case 'n':
			return s.scanLiteral("null")
		default:
			return s.scanNumber()
		}
	}

	return nil, io.EOF
}

func (s *scanner) IsEOF() bool {
	return s.i >= len(s.p)
}

func (s *scanner) scanString() ([]byte, error) {
	start := s.i
	s.i++ // skip opening "

	for s.i < len(s.p) {
		c := s.p[s.i]
		if c < 0x20 {
			return nil, fmt.Errorf("invalid control character at offset %d", s.i)
		}

		if c == '\\' {
			s.i++
			if err := s.scanEscape(); err != nil {
				return nil, err
			}
			continue
		}

		if c == '"' {
			s.i++

			// we want the quotes here because this lets the token consumer
			// know that it's a string without additional identifying data
			// (e.g. a token type enum)
			return s.p[start:s.i], nil
		}

		s.i++
	}
	return nil, fmt.Errorf("unterminated string at offset %d", start)
}

func (s *scanner) scanEscape() error {
	if s.i >= len(s.p) {
		return fmt.Errorf("unterminated escape at offset %d", s.i-1)
	}
	c := s.p[s.i]
	s.i++
	switch c {
	case '"', '\\', '/', 'b', 'f', 'n', 'r', 't':
		return nil
	case 'u':
		return s.scanUnicodeEscape()
	default:
		return fmt.Errorf("invalid escape character '%c' at offset %d", c, s.i-1)
	}
}

func (s *scanner) scanUnicodeEscape() error {
	if s.i+4 > len(s.p) {
		return fmt.Errorf("incomplete unicode escape at offset %d", s.i-2)
	}
	for _, c := range s.p[s.i : s.i+4] {
		if !('0' <= c && c <= '9' || 'a' <= c && c <= 'f' || 'A' <= c && c <= 'F') {
			return fmt.Errorf("invalid character '%c' in unicode escape at offset %d", c, s.i-2)
		}
	}
	s.i += 4
	return nil
}

func (s *scanner) scanNumber() ([]byte, error) {
	start := s.i
	if s.i < len(s.p) && s.p[s.i] == '-' {
		s.i++
	}
	digitStart := s.i
	s.scanDigits()
	if s.i == digitStart {
		return nil, fmt.Errorf("unexpected token at offset %d", start)
	}
	if s.p[digitStart] == '0' && s.i-digitStart > 1 {
		return nil, fmt.Errorf("leading zeros not allowed at offset %d", digitStart)
	}

	if s.i < len(s.p) && s.p[s.i] == '.' {
		s.i++
		digitStart = s.i
		s.scanDigits()
		if s.i == digitStart {
			return nil, fmt.Errorf("no digits after decimal point at offset %d", s.i)
		}
	}

	if s.i < len(s.p) && (s.p[s.i] == 'e' || s.p[s.i] == 'E') {
		s.i++
		if s.i < len(s.p) && (s.p[s.i] == '+' || s.p[s.i] == '-') {
			s.i++
		}
		digitStart = s.i
		s.scanDigits()
		if s.i == digitStart {
			return nil, fmt.Errorf("no digits after exponent at offset %d", s.i)
		}
	}

	return s.p[start:s.i], nil
}

func (s *scanner) scanDigits() {
	for s.i < len(s.p) && s.p[s.i] >= '0' && s.p[s.i] <= '9' {
		s.i++
	}
}

func (s *scanner) scanLiteral(lit string) ([]byte, error) {
	end := s.i + len(lit)
	if end > len(s.p) || string(s.p[s.i:end]) != lit {
		return nil, fmt.Errorf("invalid literal at offset %d", s.i)
	}
	start := s.i
	s.i = end
	return s.p[start:end], nil
}

var whitespace = [256]bool{
	' ':  true,
	'\t': true,
	'\n': true,
	'\r': true,
}
