package json

import (
	"encoding/base64"
	"math"
	"math/big"
	"strconv"
	"sync"
	"time"
	"unicode/utf8"
	"unsafe"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/document"
	smithydocumentjson "github.com/aws/smithy-go/document/json"
	"github.com/aws/smithy-go/encoding"
	"github.com/aws/smithy-go/internal/bignum"
	smithytime "github.com/aws/smithy-go/time"
	"github.com/aws/smithy-go/traits"
)

// Options configures JSON shape serialization and deserialization.
type Options struct {
	// Controls whether the @jsonName trait is used to determine JSON object
	// keys. If false (the default), the member name is used as-is.
	UseJSONName bool

	// Controls how arbitrary-precision numbers (bigInteger and bigDecimal)
	// are encoded.
	//
	// When false (the default) they are encoded as JSON numbers, which is
	// lossy for consumers that coerce JSON numbers to double-precision
	// floats. When true they are encoded as JSON strings holding the value at
	// its full precision.
	//
	// Deserialization accepts both forms regardless of this setting.
	UseStringForArbitraryPrecision bool

	// Controls whether the @timestampFormat trait is honored.
	//
	// When false (the default) the trait selects the timestamp encoding. When
	// true the trait is ignored and timestamps are always epoch-seconds JSON
	// numbers, which is what rpcv2Json requires.
	IgnoreTimestampFormat bool
}

type serCtx struct {
	comma    bool
	inObject bool
}

// ShapeSerializer implements marshaling of Smithy shapes to JSON.
// It writes directly to a []byte buffer without intermediate allocations.
type ShapeSerializer struct {
	buf       []byte
	opts      Options
	stack     []serCtx
	depth     int
	noKey     bool
	initStack [64]serCtx

	// rootSchema is the schema passed to the first WriteStruct call, used by
	// protocols that must distinguish "no input" from "empty input".
	rootSchema *smithy.Schema
}

const (
	defaultBufSize  = 1024
	maxCacheableBuf = defaultBufSize * 4
)

var serPool = sync.Pool{
	New: func() any {
		s := &ShapeSerializer{
			buf: make([]byte, 0, defaultBufSize),
		}
		s.stack = s.initStack[:1]
		return s
	},
}

var _ smithy.ShapeSerializer = (*ShapeSerializer)(nil)

// NewShapeSerializer creates a new ShapeSerializer.
func NewShapeSerializer(opts ...func(*Options)) *ShapeSerializer {
	o := Options{}
	for _, fn := range opts {
		fn(&o)
	}
	s := serPool.Get().(*ShapeSerializer)
	s.buf = s.buf[:0]
	s.opts = o
	s.depth = 0
	s.noKey = false
	s.rootSchema = nil
	s.stack = s.initStack[:1]
	s.stack[0] = serCtx{}
	return s
}

// IsUnitShape reports whether the serialized content represents a Unit shape,
// i.e. an operation with no modeled input.
func (s *ShapeSerializer) IsUnitShape() bool {
	if s.rootSchema == nil {
		return false
	}
	_, ok := smithy.SchemaTrait[*traits.UnitShape](s.rootSchema)
	return ok
}

// Close returns the serializer to the pool for reuse.
func (s *ShapeSerializer) Close() {
	if cap(s.buf) > maxCacheableBuf {
		s.buf = make([]byte, 0, defaultBufSize)
	}
	serPool.Put(s)
}

// Bytes returns a copy of the serialized JSON bytes, safe to retain after Close().
func (s *ShapeSerializer) Bytes() []byte {
	return append([]byte(nil), s.buf...)
}

func (s *ShapeSerializer) writeComma() {
	if s.depth > 0 && s.stack[s.depth].comma {
		s.buf = append(s.buf, ',')
	}
	s.stack[s.depth].comma = true
}

// writePrefix handles the key-or-comma logic before a value, respecting noKey.
func (s *ShapeSerializer) writePrefix(schema *smithy.Schema) {
	if s.noKey {
		s.noKey = false
		return
	}
	if schema != nil && s.depth > 0 && s.stack[s.depth].inObject {
		s.writeKey(schema)
	} else {
		s.writeComma()
	}
}

func (s *ShapeSerializer) writeKey(schema *smithy.Schema) {
	ext := getExt(schema)
	if s.opts.UseJSONName {
		if jk := ext.jsonNameKey; jk != nil {
			if s.stack[s.depth].comma {
				s.buf = append(s.buf, jk...)
			} else {
				s.stack[s.depth].comma = true
				s.buf = append(s.buf, jk[1:]...)
			}
			return
		}
	}
	jk := ext.jsonKey
	if len(jk) == 0 {
		s.writeComma()
		return
	}
	if s.stack[s.depth].comma {
		s.buf = append(s.buf, jk...)
	} else {
		s.stack[s.depth].comma = true
		s.buf = append(s.buf, jk[1:]...)
	}
}

// WriteBool implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteBool(schema *smithy.Schema, v bool) {
	s.writePrefix(schema)
	if v {
		s.buf = append(s.buf, "true"...)
	} else {
		s.buf = append(s.buf, "false"...)
	}
}

// WriteInt8 implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt8(schema *smithy.Schema, v int8) {
	s.WriteInt64(schema, int64(v))
}

// WriteInt16 implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt16(schema *smithy.Schema, v int16) {
	s.WriteInt64(schema, int64(v))
}

// WriteInt32 implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt32(schema *smithy.Schema, v int32) {
	s.WriteInt64(schema, int64(v))
}

// WriteInt64 implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt64(schema *smithy.Schema, v int64) {
	s.writePrefix(schema)
	s.buf = strconv.AppendInt(s.buf, v, 10)
}

// WriteFloat32 implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteFloat32(schema *smithy.Schema, v float32) {
	s.writePrefix(schema)
	if math.IsInf(float64(v), 1) {
		s.buf = append(s.buf, `"Infinity"`...)
	} else if math.IsInf(float64(v), -1) {
		s.buf = append(s.buf, `"-Infinity"`...)
	} else if math.IsNaN(float64(v)) {
		s.buf = append(s.buf, `"NaN"`...)
	} else {
		s.buf = encoding.EncodeFloat(s.buf, float64(v), 32)
	}
}

// WriteFloat64 implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteFloat64(schema *smithy.Schema, v float64) {
	s.writePrefix(schema)
	if math.IsInf(v, 1) {
		s.buf = append(s.buf, `"Infinity"`...)
	} else if math.IsInf(v, -1) {
		s.buf = append(s.buf, `"-Infinity"`...)
	} else if math.IsNaN(v) {
		s.buf = append(s.buf, `"NaN"`...)
	} else {
		s.buf = encoding.EncodeFloat(s.buf, v, 64)
	}
}

// WriteString implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteString(schema *smithy.Schema, v string) {
	s.writePrefix(schema)
	s.appendEscapedString(v)
}

// WriteBlob implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteBlob(schema *smithy.Schema, v []byte) {
	s.writePrefix(schema)
	if v == nil {
		s.buf = append(s.buf, "null"...)
		return
	}
	s.buf = append(s.buf, '"')
	encodedLen := base64.StdEncoding.EncodedLen(len(v))
	start := len(s.buf)
	s.buf = append(s.buf, make([]byte, encodedLen)...)
	base64.StdEncoding.Encode(s.buf[start:], v)
	s.buf = append(s.buf, '"')
}

// WriteList implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteList(schema *smithy.Schema) {
	s.writePrefix(schema)
	s.buf = append(s.buf, '[')
	s.depth++
	s.stack = append(s.stack, serCtx{})
}

// CloseList implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) CloseList() {
	s.buf = append(s.buf, ']')
	s.stack = s.stack[:s.depth]
	s.depth--
}

// WriteMap implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteMap(schema *smithy.Schema) {
	s.writePrefix(schema)
	s.buf = append(s.buf, '{')
	s.depth++
	s.stack = append(s.stack, serCtx{inObject: true})
}

// WriteKey implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteKey(_ *smithy.Schema, key string) {
	s.writeComma()
	s.appendEscapedString(key)
	s.buf = append(s.buf, ':')
	s.noKey = true
}

// CloseMap implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) CloseMap() {
	s.buf = append(s.buf, '}')
	s.stack = s.stack[:s.depth]
	s.depth--
}

// WriteTime implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteTime(schema *smithy.Schema, v time.Time) {
	format := "epoch-seconds"
	if t, ok := smithy.SchemaTrait[*traits.TimestampFormat](schema); ok && !s.opts.IgnoreTimestampFormat {
		format = t.Format
	}

	switch format {
	case "date-time":
		s.WriteString(schema, smithytime.FormatDateTime(v))
	case "http-date":
		s.WriteString(schema, smithytime.FormatHTTPDate(v))
	default:
		s.WriteFloat64(schema, smithytime.FormatEpochSeconds(v))
	}
}

// WriteUnion implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteUnion(schema, variant *smithy.Schema) {
	s.writePrefix(schema)
	s.buf = append(s.buf, '{')
	s.depth++
	s.stack = append(s.stack, serCtx{inObject: true})
	s.writeKey(variant)
	s.noKey = true
}

// CloseUnion implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) CloseUnion() {
	s.noKey = false
	s.buf = append(s.buf, '}')
	s.stack = s.stack[:s.depth]
	s.depth--
}

// WriteStruct implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteStruct(schema *smithy.Schema) {
	if s.rootSchema == nil && s.depth == 0 {
		s.rootSchema = schema
	}
	s.writePrefix(schema)
	s.buf = append(s.buf, '{')
	s.depth++
	s.stack = append(s.stack, serCtx{inObject: true})
}

// CloseStruct implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) CloseStruct() {
	s.buf = append(s.buf, '}')
	s.stack = s.stack[:s.depth]
	s.depth--
}

// WriteNil implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteNil(schema *smithy.Schema) {
	s.writePrefix(schema)
	s.buf = append(s.buf, "null"...)
}

// WriteBigInt implements [smithy.ShapeSerializer].
//
// The value is written as a JSON string when
// [Options.UseStringForArbitraryPrecision] is set, else as a JSON number. In
// both cases the value's full decimal text is emitted, so no precision is
// lost by this codec.
func (s *ShapeSerializer) WriteBigInt(schema *smithy.Schema, v *big.Int) {
	if v == nil {
		s.WriteNil(schema)
		return
	}
	s.writeBignumText(schema, v.Append(nil, 10))
}

// WriteBigDecimal implements [smithy.ShapeSerializer].
//
// The value is written as a JSON string when
// [Options.UseStringForArbitraryPrecision] is set, else as a JSON number. In
// both cases the value's full decimal text is emitted, so no precision or
// scale is lost by this codec.
func (s *ShapeSerializer) WriteBigDecimal(schema *smithy.Schema, v smithy.BigDecimal) {
	if v.Mantissa == nil {
		s.WriteNil(schema)
		return
	}
	text, err := bignum.FormatDecimal(v)
	if err != nil {
		// Exp is out of the range FormatDecimal will shift; there is no
		// sensible text to emit.
		s.WriteNil(schema)
		return
	}
	s.writeBignumText(schema, text)
}

// writeBignumText emits arbitrary-precision decimal text, as a JSON string or a
// bare JSON number depending on the codec options.
func (s *ShapeSerializer) writeBignumText(schema *smithy.Schema, text []byte) {
	if text == nil {
		s.WriteNil(schema)
		return
	}

	s.writePrefix(schema)
	if s.opts.UseStringForArbitraryPrecision {
		s.buf = append(s.buf, '"')
		s.buf = append(s.buf, text...)
		s.buf = append(s.buf, '"')
		return
	}
	s.buf = append(s.buf, text...)
}

// WriteDocument writes a document value to JSON.
func (s *ShapeSerializer) WriteDocument(schema *smithy.Schema, v document.Value) {
	switch vv := v.(type) {
	case document.Null:
		s.WriteNil(schema)
	case document.Boolean:
		s.WriteBool(schema, bool(vv))
	case document.Number:
		s.writeRaw(schema, []byte(vv))
	case document.String:
		s.WriteString(schema, string(vv))
	case document.Blob:
		s.WriteBlob(schema, []byte(vv))
	case document.Timestamp:
		s.WriteTime(schema, time.Time(vv))
	case document.List:
		s.WriteList(schema)
		for _, item := range vv {
			s.WriteDocument(schema.ListMember(), item)
		}
		s.CloseList()
	case document.Map:
		s.WriteMap(schema)
		for k, item := range vv {
			s.WriteKey(schema.MapKey(), k)
			s.WriteDocument(schema.MapValue(), item)
		}
		s.CloseMap()
	case document.Structure:
		s.WriteMap(schema)
		for k, item := range vv.Members {
			s.WriteKey(nil, k)
			s.WriteDocument(nil, item)
		}
		s.CloseMap()
	case document.Opaque:
		s.writeOpaqueDocument(schema, vv.Value)
	case *document.Opaque:
		s.writeOpaqueDocument(schema, vv.Value)
	}
}

func (s *ShapeSerializer) writeOpaqueDocument(schema *smithy.Schema, v any) {
	if m, ok := v.(document.Marshaler); ok {
		p, _ := m.MarshalSmithyDocument()
		s.writeRaw(schema, p)
		return
	}
	denc := smithydocumentjson.NewEncoder()
	p, _ := denc.Encode(v)
	s.writeRaw(schema, p)
}

func (s *ShapeSerializer) writeRaw(schema *smithy.Schema, p []byte) {
	s.writePrefix(schema)
	s.buf = append(s.buf, p...)
}

// jsonMemberName returns the JSON key for a schema member.
func (s *ShapeSerializer) jsonMemberName(schema *smithy.Schema) string {
	if s.opts.UseJSONName {
		if jn, ok := smithy.SchemaTrait[*traits.JSONName](schema); ok {
			return jn.Name
		}
	}
	return schema.MemberName()
}

// appendEscapedString writes a JSON-escaped string to the buffer.
func (s *ShapeSerializer) appendEscapedString(v string) {
	s.buf = append(s.buf, '"')

	// fast path: SWAR check if entire string is safe ASCII
	i := 0
	p := unsafe.StringData(v)
	for i+8 <= len(v) {
		w := *(*uint64)(unsafe.Pointer(uintptr(unsafe.Pointer(p)) + uintptr(i)))
		// high bit set means >= 0x80, hasLess detects control chars,
		// hasValue detects '"', '\\', and DEL (0x7F)
		if w&hi|hasLess(w, 0x20)|hasValue(w, '"')|hasValue(w, '\\')|hasValue(w, 0x7F) != 0 {
			break
		}
		i += 8
	}
	for ; i < len(v); i++ {
		if v[i] >= utf8.RuneSelf || !safeSet[v[i]] {
			break
		}
	}
	if i == len(v) {
		s.buf = append(s.buf, v...)
		s.buf = append(s.buf, '"')
		return
	}

	// write the safe prefix we already validated, escape from i onward
	s.buf = append(s.buf, v[:i]...)
	start := i
	for i < len(v) {
		b := v[i]
		if b < utf8.RuneSelf {
			if safeSet[b] {
				i++
				continue
			}
			if start < i {
				s.buf = append(s.buf, v[start:i]...)
			}
			switch b {
			case '\\', '"':
				s.buf = append(s.buf, '\\', b)
			case '\n':
				s.buf = append(s.buf, '\\', 'n')
			case '\r':
				s.buf = append(s.buf, '\\', 'r')
			case '\t':
				s.buf = append(s.buf, '\\', 't')
			default:
				s.buf = append(s.buf, '\\', 'u', '0', '0', hex[b>>4], hex[b&0xF])
			}
			i++
			start = i
			continue
		}
		c, size := utf8.DecodeRuneInString(v[i:])
		if c == utf8.RuneError && size == 1 {
			if start < i {
				s.buf = append(s.buf, v[start:i]...)
			}
			s.buf = append(s.buf, `�`...)
			i += size
			start = i
			continue
		}
		if c == '\u2028' || c == '\u2029' {
			if start < i {
				s.buf = append(s.buf, v[start:i]...)
			}
			s.buf = append(s.buf, '\\', 'u', '2', '0', '2', hex[c&0xF])
			i += size
			start = i
			continue
		}
		i += size
	}
	if start < len(v) {
		s.buf = append(s.buf, v[start:]...)
	}
	s.buf = append(s.buf, '"')
}

var safeSet = [utf8.RuneSelf]bool{
	' ':  true,
	'!':  true,
	'"':  false,
	'#':  true,
	'$':  true,
	'%':  true,
	'&':  true,
	'\'': true,
	'(':  true,
	')':  true,
	'*':  true,
	'+':  true,
	',':  true,
	'-':  true,
	'.':  true,
	'/':  true,
	'0':  true,
	'1':  true,
	'2':  true,
	'3':  true,
	'4':  true,
	'5':  true,
	'6':  true,
	'7':  true,
	'8':  true,
	'9':  true,
	':':  true,
	';':  true,
	'<':  true,
	'=':  true,
	'>':  true,
	'?':  true,
	'@':  true,
	'A':  true,
	'B':  true,
	'C':  true,
	'D':  true,
	'E':  true,
	'F':  true,
	'G':  true,
	'H':  true,
	'I':  true,
	'J':  true,
	'K':  true,
	'L':  true,
	'M':  true,
	'N':  true,
	'O':  true,
	'P':  true,
	'Q':  true,
	'R':  true,
	'S':  true,
	'T':  true,
	'U':  true,
	'V':  true,
	'W':  true,
	'X':  true,
	'Y':  true,
	'Z':  true,
	'[':  true,
	'\\': false,
	']':  true,
	'^':  true,
	'_':  true,
	'`':  true,
	'a':  true,
	'b':  true,
	'c':  true,
	'd':  true,
	'e':  true,
	'f':  true,
	'g':  true,
	'h':  true,
	'i':  true,
	'j':  true,
	'k':  true,
	'l':  true,
	'm':  true,
	'n':  true,
	'o':  true,
	'p':  true,
	'q':  true,
	'r':  true,
	's':  true,
	't':  true,
	'u':  true,
	'v':  true,
	'w':  true,
	'x':  true,
	'y':  true,
	'z':  true,
	'{':  true,
	'|':  true,
	'}':  true,
	'~':  true,
	'':  false,
}

var hex = "0123456789abcdef"
