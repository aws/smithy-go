package json

import (
	"sort"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/traits"
)

type jsonExt struct {
	jsonKey     []byte // `,"memberName":` -- use [1:] when no comma needed
	jsonNameKey []byte // `,"jsonName":` -- use [1:] when no comma needed (nil if no @jsonName)

	memberList []memberEntry
	byteIndex  *[256]int16
}

type memberEntry struct {
	name   string
	schema *smithy.Schema
}

func getExt(s *smithy.Schema) *jsonExt {
	return smithy.SchemaExtension(s, smithy.ExtJSON, buildJSONExt)
}

func buildJSONExt(s *smithy.Schema) *jsonExt {
	ext := &jsonExt{}

	if name := s.MemberName(); name != "" {
		ext.jsonKey = encodeJSONKey(name)
		if jn, ok := smithy.SchemaTrait[*traits.JSONName](s); ok {
			ext.jsonNameKey = encodeJSONKey(jn.Name)
		}
	}

	if members := s.Members(); len(members) > 0 {
		names := make([]string, 0, len(members))
		for name := range members {
			names = append(names, name)
		}
		sort.Strings(names)

		ext.memberList = make([]memberEntry, len(names))
		idx := &[256]int16{}
		for i := range idx {
			idx[i] = -1
		}
		for pos, name := range names {
			ext.memberList[pos] = memberEntry{name: name, schema: members[name]}
			if len(name) > 0 {
				b := name[0]
				if idx[b] == -1 {
					idx[b] = int16(pos)
				} else {
					idx[b] = -2
				}
			}
		}
		ext.byteIndex = idx
	}

	return ext
}

func memberByBytes(s *smithy.Schema, name []byte) *smithy.Schema {
	ext := getExt(s)
	if ext.byteIndex == nil || len(name) == 0 {
		return nil
	}
	idx := ext.byteIndex[name[0]]
	if idx == -1 {
		return nil
	}
	if idx >= 0 {
		e := &ext.memberList[idx]
		if len(e.name) == len(name) && e.name == string(name) {
			return e.schema
		}
		return nil
	}
	for i := range ext.memberList {
		e := &ext.memberList[i]
		if len(e.name) == len(name) && e.name == string(name) {
			return e.schema
		}
	}
	return nil
}

func encodeJSONKey(name string) []byte {
	buf := make([]byte, 0, len(name)+4)
	buf = append(buf, ',', '"')
	for i := 0; i < len(name); i++ {
		c := name[i]
		switch c {
		case '"':
			buf = append(buf, '\\', '"')
		case '\\':
			buf = append(buf, '\\', '\\')
		case '\n':
			buf = append(buf, '\\', 'n')
		case '\r':
			buf = append(buf, '\\', 'r')
		case '\t':
			buf = append(buf, '\\', 't')
		default:
			if c < 0x20 {
				buf = append(buf, '\\', 'u', '0', '0', "0123456789abcdef"[c>>4], "0123456789abcdef"[c&0xF])
			} else {
				buf = append(buf, c)
			}
		}
	}
	buf = append(buf, '"', ':')
	return buf
}
