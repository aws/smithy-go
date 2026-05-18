package json

import (
	"errors"
	"fmt"
	"io"
	"strconv"

	"github.com/aws/smithy-go/internal/serde"
)

// matches stdlib
const maxDepth = 10_000

const (
	inObject int8 = iota + 1
	inArray
)

func errUnexpectedToken(c byte) error {
	return fmt.Errorf("unexpected token '%c'", c)
}

// parser wraps scanner to pull tokens from a JSON body but also validate
// that the syntax of the JSON is valid.
type parser struct {
	tok   scanner
	stack serde.Stack[int8]
	done  bool

	// parse changes as tokens are called to handle state transitions
	//
	// per the Dave Cheney article, https://www.json.org/json-en.html has flow
	// diagrams that help us build out the state transitions
	parse func(*parser, []byte) ([]byte, error)
}

func (p *parser) Next() ([]byte, error) {
	if p.done {
		return nil, io.EOF
	}

	next, err := p.tok.Next()
	if err != nil {
		if err == io.EOF {
			return nil, fmt.Errorf("unexpected end of JSON input")
		}
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
		if p.stack.Len() > maxDepth {
			return nil, errors.New("exceeded max nesting depth")
		}
		return tok, nil
	case '[':
		p.parse = (*parser).parseArrayValue
		p.stack.Push(inArray)
		if p.stack.Len() > maxDepth {
			return nil, errors.New("exceeded max nesting depth")
		}
		return tok, nil
	case ',', ':', '}', ']':
		return nil, errUnexpectedToken(tok[0])
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
		return nil, errUnexpectedToken(tok[0])
	}
}

func (p *parser) parseObjectColon(tok []byte) ([]byte, error) {
	if tok[0] != ':' {
		return nil, errUnexpectedToken(tok[0])
	}
	p.parse = (*parser).parseValue
	return p.Next()
}

func (p *parser) parseObjectComma(tok []byte) ([]byte, error) {
	switch tok[0] {
	case ',':
		p.parse = (*parser).parseObjectKeyAfterComma
		return p.Next()
	case '}':
		p.close()
		return tok, nil
	default:
		return nil, errUnexpectedToken(tok[0])
	}
}

func (p *parser) parseObjectKeyAfterComma(tok []byte) ([]byte, error) {
	if tok[0] != '"' {
		return nil, errUnexpectedToken(tok[0])
	}
	p.parse = (*parser).parseObjectColon
	return tok, nil
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
		return nil, errUnexpectedToken(tok[0])
	}
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
		return unquote(tok)
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
			key, err := unquote(ktok)
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
	top := p.stack.Top()
	if top == nil {
		p.done = true
		return
	}

	switch *top {
	case inObject:
		p.parse = (*parser).parseObjectComma
	case inArray:
		p.parse = (*parser).parseArrayComma
	default:
		p.done = true
	}
}
