package json

import (
	"fmt"
	"io"
	"strconv"
)

// tokenizer scans json tokens without the allocation overhead of json.Token.
// It does not care about the syntactic validity of the json at all, that is
// handled by [parser].
//
// Largely inspired by https://dave.cheney.net/paste/gophercon-sg-2023.html
// although this version operates on a fully buffered input since we are
// generally dealing with smaller RPC-style payloads.
type tokenizer struct {
	p []byte
	i int
}

func (s *tokenizer) Next() ([]byte, error) {
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

func (s *tokenizer) IsEOF() bool {
	return s.i >= len(s.p)
}

func (s *tokenizer) scanString() ([]byte, error) {
	start := s.i
	s.i++ // skip opening "

	for s.i < len(s.p) {
		c := s.p[s.i]
		if ctrlchars[c] {
			return nil, fmt.Errorf("invalid control character at offset %d", s.i)
		}

		if c == '\\' { // escaped char
			s.i += 2
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

func (s *tokenizer) scanNumber() ([]byte, error) {
	start := s.i
	if s.i < len(s.p) && s.p[s.i] == '-' {
		s.i++
	}
	s.scanDigits()
	if s.i < len(s.p) && s.p[s.i] == '.' {
		s.i++
		s.scanDigits()
	}
	if s.i < len(s.p) && (s.p[s.i] == 'e' || s.p[s.i] == 'E') {
		s.i++
		if s.i < len(s.p) && (s.p[s.i] == '+' || s.p[s.i] == '-') {
			s.i++
		}
		s.scanDigits()
	}
	if s.i == start {
		return nil, fmt.Errorf("unexpected token at offset %d", start)
	}
	return s.p[start:s.i], nil
}

func (s *tokenizer) scanDigits() {
	for s.i < len(s.p) && s.p[s.i] >= '0' && s.p[s.i] <= '9' {
		s.i++
	}
}

func (s *tokenizer) scanLiteral(lit string) ([]byte, error) {
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

var ctrlchars = [256]bool{
	'\n': true,
	'\r': true,
}

const (
	inObject = iota
	inArray
)

// parser wraps tokenizer to pull tokens from a JSON body but also validate
// that the syntax of the JSON is valid.
type parser struct {
	tok   tokenizer
	stack stackT[int8]

	// parse changes as tokens are called to handle state transitions
	//
	// per the Dave Cheney article, https://www.json.org/json-en.html has flow
	// diagrams that help us build out the state transitions
	parse func(*parser, []byte) ([]byte, error)
}

func (p *parser) Next() ([]byte, error) {
	next, err := p.tok.Next()
	if err != nil {
		return nil, err
	}

	return p.parse(p, next)
}

func (p *parser) Skip() error {
	var depth int
	for {
		tok, err := p.Next()
		if err != nil {
			return err
		}
		switch tok[0] {
		case '{', '[':
			depth++
		case '}', ']':
			depth--
		}
		if depth == 0 {
			return nil
		}
	}
}

func (p *parser) Value() (any, error) {
	tok, err := p.Next()
	if err != nil {
		return nil, err
	}
	return p.value(tok)
}

func (p *parser) parseValue(tok []byte) ([]byte, error) {
	switch tok[0] {
	case '{':
		p.parse = (*parser).parseObjectKey
		p.stack.Push(inObject)
		return tok, nil
	case '[':
		p.parse = (*parser).parseArrayValue
		p.stack.Push(inArray)
		return tok, nil
	case ',', ':', '}', ']':
		return nil, fmt.Errorf("unexpected '%c' in json value", tok[0])
	}

	p.afterValue()
	return tok, nil
}

func (p *parser) parseObjectKey(tok []byte) ([]byte, error) {
	switch tok[0] {
	case '"':
		p.parse = (*parser).parseObjectColon
		return tok, nil
	case '}':
		p.close()
		return tok, nil
	default:
		return nil, fmt.Errorf("unexpected '%c' in json object key", tok[0])
	}
}

func (p *parser) parseObjectColon(tok []byte) ([]byte, error) {
	if tok[0] != ':' {
		return nil, fmt.Errorf("expected ':', got '%c'", tok[0])
	}
	p.parse = (*parser).parseValue
	return p.Next()
}

func (p *parser) parseObjectComma(tok []byte) ([]byte, error) {
	switch tok[0] {
	case ',':
		p.parse = (*parser).parseObjectKey
		return p.Next()
	case '}':
		p.close()
		return tok, nil
	default:
		return nil, fmt.Errorf("unexpected '%c' in json object", tok[0])
	}
}

func (p *parser) parseArrayValue(tok []byte) ([]byte, error) {
	if tok[0] == ']' {
		p.close()
		return tok, nil
	}
	return p.parseValue(tok)
}

func (p *parser) parseArrayComma(tok []byte) ([]byte, error) {
	switch tok[0] {
	case ',':
		p.parse = (*parser).parseValue
		return p.Next()
	case ']':
		p.close()
		return tok, nil
	default:
		return nil, fmt.Errorf("unexpected '%c' in json array", tok[0])
	}
}

func (p *parser) eof(tok []byte) ([]byte, error) {
	return nil, io.EOF
}

func (p *parser) value(tok []byte) (any, error) {
	switch tok[0] {
	case 'n':
		return nil, nil
	case 't':
		return true, nil
	case 'f':
		return false, nil
	case '"':
		return strconv.Unquote(string(tok))
	case '{':
		m := map[string]any{}
		for {
			ktok, err := p.Next()
			if err != nil {
				return nil, err
			}
			if ktok[0] == '}' {
				return m, nil
			}
			key, err := strconv.Unquote(string(ktok))
			if err != nil {
				return nil, err
			}
			val, err := p.Value()
			if err != nil {
				return nil, err
			}
			m[key] = val
		}
	case '[':
		var list []any
		for {
			tok, err := p.Next()
			if err != nil {
				return nil, err
			}
			if tok[0] == ']' {
				if list == nil {
					list = []any{}
				}
				return list, nil
			}
			val, err := p.value(tok)
			if err != nil {
				return nil, err
			}
			list = append(list, val)
		}
	default:
		return strconv.ParseFloat(string(tok), 64)
	}
}

func (p *parser) close() {
	p.stack.Pop()
	p.afterValue()
}

func (p *parser) afterValue() {
	switch p.stack.Top() {
	case inObject:
		p.parse = (*parser).parseObjectComma
	case inArray:
		p.parse = (*parser).parseArrayComma
	default:
		p.parse = (*parser).eof
	}
}
