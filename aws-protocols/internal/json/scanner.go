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
	i := s.i
	for ; i < len(s.p); i++ {
		c := s.p[i]
		if whitespace[c] {
			continue
		}
		if delim[c] {
			i++
			s.i = i
			return s.p[i-1 : i], nil
		}

		s.i = i
		switch c {
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

	s.i = i
	return nil, io.EOF
}

func (s *scanner) IsEOF() bool {
	return s.i >= len(s.p)
}

func (s *scanner) scanString() ([]byte, error) {
	start := s.i
	i := s.i + 1 // skip opening "

	for i < len(s.p) {
		c := s.p[i]
		if c < 0x20 {
			s.i = i
			return nil, fmt.Errorf("invalid control character at offset %d", i)
		}

		if c == '\\' {
			s.i = i + 1
			if err := s.scanEscape(); err != nil {
				return nil, err
			}
			i = s.i
			continue
		}

		if c == '"' {
			i++
			s.i = i

			// we want the quotes here because this lets the token consumer
			// know that it's a string without additional identifying data
			// (e.g. a token type enum)
			return s.p[start:i], nil
		}

		i++
	}
	s.i = i
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
	i := s.i
	if i < len(s.p) && s.p[i] == '-' {
		i++
	}
	digitStart := i
	for i < len(s.p) && s.p[i] >= '0' && s.p[i] <= '9' {
		i++
	}
	if i == digitStart {
		return nil, fmt.Errorf("unexpected token at offset %d", s.i)
	}
	if s.p[digitStart] == '0' && i-digitStart > 1 {
		return nil, fmt.Errorf("leading zeros not allowed at offset %d", digitStart)
	}

	if i < len(s.p) && s.p[i] == '.' {
		i++
		digitStart = i
		for i < len(s.p) && s.p[i] >= '0' && s.p[i] <= '9' {
			i++
		}
		if i == digitStart {
			return nil, fmt.Errorf("no digits after decimal point at offset %d", i)
		}
	}

	if i < len(s.p) && (s.p[i] == 'e' || s.p[i] == 'E') {
		i++
		if i < len(s.p) && (s.p[i] == '+' || s.p[i] == '-') {
			i++
		}
		digitStart = i
		for i < len(s.p) && s.p[i] >= '0' && s.p[i] <= '9' {
			i++
		}
		if i == digitStart {
			return nil, fmt.Errorf("no digits after exponent at offset %d", i)
		}
	}

	start := s.i
	s.i = i
	return s.p[start:i], nil
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

var delim = [256]bool{
	'{': true,
	'}': true,
	'[': true,
	']': true,
	':': true,
	',': true,
}
