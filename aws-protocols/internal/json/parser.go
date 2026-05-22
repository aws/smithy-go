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

type parseState uint8

const (
	stValue parseState = iota
	stObjectKey
	stObjectColon
	stObjectComma
	stObjectKeyAfterComma
	stArrayValue
	stArrayComma
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
	state parseState
}

func (p *parser) Next() ([]byte, error) {
	if p.done {
		return nil, io.EOF
	}

	for {
		next, err := p.tok.Next()
		if err != nil {
			if err == io.EOF {
				return nil, fmt.Errorf("unexpected end of JSON input")
			}
			return nil, err
		}

		switch p.state {
		case stValue:
			switch next[0] {
			case '{':
				p.state = stObjectKey
				p.stack.Push(inObject)
				if p.stack.Len() > maxDepth {
					return nil, errors.New("exceeded max nesting depth")
				}
				return next, nil
			case '[':
				p.state = stArrayValue
				p.stack.Push(inArray)
				if p.stack.Len() > maxDepth {
					return nil, errors.New("exceeded max nesting depth")
				}
				return next, nil
			case ',', ':', '}', ']':
				return nil, errUnexpectedToken(next[0])
			}
			p.afterValue()
			return next, nil

		case stObjectKey:
			switch next[0] {
			case '"':
				p.state = stObjectColon
				return next, nil
			case '}':
				p.close()
				return next, nil
			default:
				return nil, errUnexpectedToken(next[0])
			}

		case stObjectColon:
			if next[0] != ':' {
				return nil, errUnexpectedToken(next[0])
			}
			p.state = stValue
			continue

		case stObjectComma:
			switch next[0] {
			case ',':
				p.state = stObjectKeyAfterComma
				continue
			case '}':
				p.close()
				return next, nil
			default:
				return nil, errUnexpectedToken(next[0])
			}

		case stObjectKeyAfterComma:
			if next[0] != '"' {
				return nil, errUnexpectedToken(next[0])
			}
			p.state = stObjectColon
			return next, nil

		case stArrayValue:
			if next[0] == ']' {
				p.close()
				return next, nil
			}
			// handle as value
			switch next[0] {
			case '{':
				p.state = stObjectKey
				p.stack.Push(inObject)
				if p.stack.Len() > maxDepth {
					return nil, errors.New("exceeded max nesting depth")
				}
				return next, nil
			case '[':
				p.state = stArrayValue
				p.stack.Push(inArray)
				if p.stack.Len() > maxDepth {
					return nil, errors.New("exceeded max nesting depth")
				}
				return next, nil
			case ',', ':', '}':
				return nil, errUnexpectedToken(next[0])
			}
			p.afterValue()
			return next, nil

		case stArrayComma:
			switch next[0] {
			case ',':
				p.state = stValue
				continue
			case ']':
				p.close()
				return next, nil
			default:
				return nil, errUnexpectedToken(next[0])
			}
		}
	}
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
		p.state = stObjectComma
	case inArray:
		p.state = stArrayComma
	default:
		p.done = true
	}
}
