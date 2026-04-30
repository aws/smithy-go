package query

import (
	"encoding/base64"
	"math"
	"math/big"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/document"
	smithytime "github.com/aws/smithy-go/time"
	"github.com/aws/smithy-go/traits"
)

type ctxKind byte

const (
	ctxKindList ctxKind = iota
	ctxKindMap
	ctxKindMapValue
	ctxKindStruct

	ctxKindNone ctxKind = 0xff
)

type serCtx struct {
	kind      ctxKind
	flattened bool
	prefix    string // popped back onto s.currPrefix as you pop out the stack

	listIndex  int
	listPrefix string

	mapIndex     int
	mapKeyName   string
	mapValueName string
	mapBuf       []mapBufEntry
}

// ShapeSerializer serializes Smithy shapes to the AWS query string format.
type ShapeSerializer struct {
	opts ShapeSerializerOptions

	values     url.Values
	stack      []serCtx
	currPrefix string // runs as values are written e.g. for list
}

type mapBufEntry struct {
	prefix string
	key    string
	values []kv
}

type kv struct {
	k, v string
}

// ShapeSerializerOptions configures a ShapeSerializer.
type ShapeSerializerOptions struct {
	WriteZeroValues bool

	// Adjusts serialization for the ec2Query protocol:
	//  - Member names resolve via @ec2QueryName, then @xmlName
	//    capitalized, then member name capitalized (instead of @xmlName
	//    or member name as-is).
	//  - All lists serialize as flat regardless of @xmlFlattened.
	//  - Empty lists are omitted (instead of emitting a "Key=" sentinel).
	EC2Mode bool
}

var _ smithy.ShapeSerializer = (*ShapeSerializer)(nil)

// NewShapeSerializer returns a new ShapeSerializer.
func NewShapeSerializer(action, version string, opts ...func(*ShapeSerializerOptions)) *ShapeSerializer {
	o := ShapeSerializerOptions{}
	for _, fn := range opts {
		fn(&o)
	}
	v := url.Values{}
	v.Set("Action", action)
	v.Set("Version", version)
	return &ShapeSerializer{values: v, opts: o}
}

// Bytes returns the encoded query string as bytes.
func (s *ShapeSerializer) Bytes() []byte {
	keys := make([]string, 0, len(s.values))
	for k := range s.values {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	var buf strings.Builder
	for _, k := range keys {
		for _, v := range s.values[k] {
			if buf.Len() > 0 {
				buf.WriteByte('&')
			}
			buf.WriteString(url.QueryEscape(k))
			buf.WriteByte('=')
			buf.WriteString(url.QueryEscape(v))
		}
	}
	return []byte(buf.String())
}

func (s *ShapeSerializer) top() ctxKind {
	if len(s.stack) == 0 {
		return ctxKindNone
	}
	return s.stack[len(s.stack)-1].kind
}

func (s *ShapeSerializer) push(ctx serCtx) {
	s.stack = append(s.stack, ctx)
}

func (s *ShapeSerializer) pop() serCtx {
	n := len(s.stack)
	v := s.stack[n-1]
	s.stack = s.stack[:n-1]
	return v
}

func (s *ShapeSerializer) topCtx() *serCtx {
	return &s.stack[len(s.stack)-1]
}

func (s *ShapeSerializer) withWriteZero(fn func()) {
	prev := s.opts.WriteZeroValues
	s.opts.WriteZeroValues = true
	fn()
	s.opts.WriteZeroValues = prev
}

func (s *ShapeSerializer) skipZeroValue() bool {
	if s.opts.WriteZeroValues {
		return false
	}
	if len(s.stack) == 0 {
		return false
	}

	switch s.top() {
	case ctxKindList, ctxKindMapValue:
		return false
	default:
		return true
	}
}

func (s *ShapeSerializer) memberName(schema *smithy.Schema) string {
	if s.opts.EC2Mode {
		return ec2MemberName(schema)
	}
	if xn, ok := smithy.SchemaTrait[*traits.XMLName](schema); ok {
		return xn.Name
	}
	return schema.MemberName()
}

func (s *ShapeSerializer) appendMemberPrefix(schema *smithy.Schema) {
	name := s.memberName(schema)
	if name == "" {
		return
	}
	if s.currPrefix != "" {
		s.currPrefix = s.currPrefix + "." + name
	} else {
		s.currPrefix = name
	}
}

// nexus point (alongside writeValue) through which basically every write flows
// to handle putting the appropriate prefix in place
func (s *ShapeSerializer) resolveKey(schema *smithy.Schema) string {
	switch s.top() {
	case ctxKindMapValue:
		valPrefix := s.consumeMapValue()
		return valPrefix
	case ctxKindList:
		ctx := s.topCtx()
		ctx.listIndex++
		return s.currPrefix + "." + strconv.Itoa(ctx.listIndex)
	default:
		name := s.memberName(schema)
		if name == "" {
			return s.currPrefix
		}
		if s.currPrefix == "" {
			return name
		}
		return s.currPrefix + "." + name
	}
}

func (s *ShapeSerializer) writeValue(key, value string) {
	if s.bufferMapEntry(key, value) {
		return
	}

	s.values.Add(key, value)
}

func (s *ShapeSerializer) bufferMapEntry(key, value string) bool {
	for i := len(s.stack) - 1; i >= 0; i-- {
		ctx := &s.stack[i]
		if ctx.kind != ctxKindMap {
			continue
		}
		if len(ctx.mapBuf) == 0 {
			return false
		}

		entry := &ctx.mapBuf[len(ctx.mapBuf)-1]
		suffix := strings.TrimPrefix(key, entry.prefix)
		entry.values = append(entry.values, kv{k: suffix, v: value})
		return true
	}

	return false
}

// WriteInt8Ptr implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt8Ptr(schema *smithy.Schema, v *int8) {
	writePtr(s, schema, v, (*ShapeSerializer).WriteInt8)
}

// WriteInt16Ptr implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt16Ptr(schema *smithy.Schema, v *int16) {
	writePtr(s, schema, v, (*ShapeSerializer).WriteInt16)
}

// WriteInt32Ptr implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt32Ptr(schema *smithy.Schema, v *int32) {
	writePtr(s, schema, v, (*ShapeSerializer).WriteInt32)
}

// WriteInt64Ptr implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt64Ptr(schema *smithy.Schema, v *int64) {
	writePtr(s, schema, v, (*ShapeSerializer).WriteInt64)
}

// WriteFloat32Ptr implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteFloat32Ptr(schema *smithy.Schema, v *float32) {
	writePtr(s, schema, v, (*ShapeSerializer).WriteFloat32)
}

// WriteFloat64Ptr implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteFloat64Ptr(schema *smithy.Schema, v *float64) {
	writePtr(s, schema, v, (*ShapeSerializer).WriteFloat64)
}

// WriteBoolPtr implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteBoolPtr(schema *smithy.Schema, v *bool) {
	writePtr(s, schema, v, (*ShapeSerializer).WriteBool)
}

// WriteStringPtr implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteStringPtr(schema *smithy.Schema, v *string) {
	writePtr(s, schema, v, (*ShapeSerializer).WriteString)
}

func writePtr[T any](s *ShapeSerializer, schema *smithy.Schema, v *T, write func(*ShapeSerializer, *smithy.Schema, T)) {
	if v == nil {
		return
	}

	s.withWriteZero(func() { write(s, schema, *v) })
}

// WriteBool implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteBool(schema *smithy.Schema, v bool) {
	if !v && s.skipZeroValue() {
		return
	}

	if v {
		s.writeValue(s.resolveKey(schema), "true")
	} else {
		s.writeValue(s.resolveKey(schema), "false")
	}
}

// WriteInt8 implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt8(schema *smithy.Schema, v int8) { writeInt(s, schema, v) }

// WriteInt16 implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt16(schema *smithy.Schema, v int16) { writeInt(s, schema, v) }

// WriteInt32 implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt32(schema *smithy.Schema, v int32) { writeInt(s, schema, v) }

// WriteInt64 implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteInt64(schema *smithy.Schema, v int64) { writeInt(s, schema, v) }

// WriteFloat32 implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteFloat32(schema *smithy.Schema, v float32) { writeFloat(s, schema, v) }

// WriteFloat64 implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteFloat64(schema *smithy.Schema, v float64) { writeFloat(s, schema, v) }

func writeInt[T int8 | int16 | int32 | int64](s *ShapeSerializer, schema *smithy.Schema, v T) {
	if v == 0 && s.skipZeroValue() {
		return
	}

	s.writeValue(s.resolveKey(schema), strconv.FormatInt(int64(v), 10))
}

func writeFloat[T float32 | float64](s *ShapeSerializer, schema *smithy.Schema, v T) {
	if v == 0 && s.skipZeroValue() {
		return
	}

	s.writeValue(s.resolveKey(schema), formatFloat(float64(v)))
}

func formatFloat(v float64) string {
	switch {
	case math.IsInf(v, 1):
		return "Infinity"
	case math.IsInf(v, -1):
		return "-Infinity"
	case math.IsNaN(v):
		return "NaN"
	default:
		return strconv.FormatFloat(v, 'f', -1, 64)
	}
}

// WriteString implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteString(schema *smithy.Schema, v string) {
	if v == "" && s.skipZeroValue() {
		return
	}

	s.writeValue(s.resolveKey(schema), v)
}

// WriteBlob implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteBlob(schema *smithy.Schema, v []byte) {
	if v == nil {
		return
	}

	s.writeValue(s.resolveKey(schema), base64.StdEncoding.EncodeToString(v))
}

// WriteTime implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteTime(schema *smithy.Schema, v time.Time) {
	format := "date-time"
	if t, ok := smithy.SchemaTrait[*traits.TimestampFormat](schema); ok {
		format = t.Format
	}

	var sv string
	switch format {
	case "date-time":
		sv = smithytime.FormatDateTime(v)
	case "http-date":
		sv = smithytime.FormatHTTPDate(v)
	case "epoch-seconds":
		sv = strconv.FormatFloat(smithytime.FormatEpochSeconds(v), 'f', -1, 64)
	default:
		sv = smithytime.FormatDateTime(v)
	}

	s.writeValue(s.resolveKey(schema), sv)
}

// WriteTimePtr implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteTimePtr(schema *smithy.Schema, v *time.Time) {
	if v != nil {
		s.WriteTime(schema, *v)
	}
}

// WriteStruct implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteStruct(schema *smithy.Schema) {
	saved := s.currPrefix
	switch s.top() {
	case ctxKindMapValue:
		valPrefix := s.consumeMapValue()
		s.currPrefix, saved = valPrefix, s.currPrefix
	case ctxKindList:
		ctx := s.topCtx()
		ctx.listIndex++
		s.currPrefix = s.currPrefix + "." + strconv.Itoa(ctx.listIndex)
	default:
		s.appendMemberPrefix(schema)
	}

	s.push(serCtx{kind: ctxKindStruct, prefix: saved})
}

// CloseStruct implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) CloseStruct() {
	ctx := s.pop()
	s.currPrefix = ctx.prefix
}

// WriteUnion implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteUnion(schema, variant *smithy.Schema, v smithy.Serializable) {
	saved := s.currPrefix
	s.appendMemberPrefix(schema)
	if s.top() == ctxKindMapValue {
		s.pop()
	}
	v.Serialize(s)
	s.currPrefix = saved
}

// WriteNil implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteNil(_ *smithy.Schema) {
	if s.top() == ctxKindMapValue {
		s.consumeMapValue()
	}
}

// enterContainer handles the prefix setup common to WriteList and WriteMap.
// It returns the prefix to save for restoration on close.
func (s *ShapeSerializer) enterContainer(schema *smithy.Schema) string {
	saved := s.currPrefix
	switch s.top() {
	case ctxKindMapValue:
		// WriteKey set s.prefix to the value path and pushed ctxMapValue
		// with the map prefix. Pop ctxMapValue and use its saved prefix
		// as the restore point — s.prefix (the value path) is already
		// the correct working prefix.
		ctx := s.pop()
		saved = ctx.prefix
		return saved
	case ctxKindList:
		ctx := s.topCtx()
		ctx.listIndex++
		s.currPrefix = s.currPrefix + "." + strconv.Itoa(ctx.listIndex)
	}

	s.appendMemberPrefix(schema)
	return saved
}

// WriteList implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteList(schema *smithy.Schema) {
	saved := s.enterContainer(schema)

	_, flattened := smithy.SchemaTrait[*traits.XMLFlattened](schema)
	flattened = flattened || s.opts.EC2Mode

	listPrefix := s.currPrefix
	if !flattened {
		locName := "member"
		if schema != nil {
			if lm := schema.ListMember(); lm != nil {
				if xn, ok := smithy.SchemaTrait[*traits.XMLName](lm); ok {
					locName = xn.Name
				}
			}
		}
		s.currPrefix = s.currPrefix + "." + locName
	}

	s.push(serCtx{
		kind:       ctxKindList,
		flattened:  flattened,
		prefix:     saved,
		listPrefix: listPrefix,
	})
}

// CloseList implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) CloseList() {
	if s.top() != ctxKindList {
		return
	}

	ctx := s.pop()
	if ctx.listIndex == 0 && !s.opts.EC2Mode {
		s.writeValue(ctx.listPrefix, "")
	}

	s.currPrefix = ctx.prefix
}

// WriteMap implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteMap(schema *smithy.Schema) {
	saved := s.enterContainer(schema)

	_, flattened := smithy.SchemaTrait[*traits.XMLFlattened](schema)
	if !flattened {
		s.currPrefix = s.currPrefix + ".entry"
	}

	keyLoc, valLoc := "key", "value"
	if schema != nil {
		if mk := schema.MapKey(); mk != nil {
			if xn, ok := smithy.SchemaTrait[*traits.XMLName](mk); ok {
				keyLoc = xn.Name
			}
		}
		if mv := schema.MapValue(); mv != nil {
			if xn, ok := smithy.SchemaTrait[*traits.XMLName](mv); ok {
				valLoc = xn.Name
			}
		}
	}

	s.push(serCtx{
		kind:         ctxKindMap,
		flattened:    flattened,
		prefix:       saved,
		mapKeyName:   keyLoc,
		mapValueName: valLoc,
	})
}

// WriteKey implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) WriteKey(_ *smithy.Schema, key string) {
	ctx := s.topCtx()
	ctx.mapIndex++

	prefix := s.currPrefix + "." + strconv.Itoa(ctx.mapIndex)
	ctx.mapBuf = append(ctx.mapBuf, mapBufEntry{
		prefix: prefix,
		key:    key,
	})

	s.writeValue(prefix+"."+ctx.mapKeyName, key)

	// Push ctxMapValue with the current prefix so the next value write can
	// restore it. Set s.prefix to the value path.
	s.push(serCtx{kind: ctxKindMapValue, prefix: s.currPrefix})
	s.currPrefix = prefix + "." + ctx.mapValueName
}

// CloseMap implements [smithy.ShapeSerializer].
func (s *ShapeSerializer) CloseMap() {
	if s.top() != ctxKindMap {
		return
	}

	ctx := s.pop()

	// Sort entries by map key for deterministic output.
	sort.Slice(ctx.mapBuf, func(i, j int) bool {
		return ctx.mapBuf[i].key < ctx.mapBuf[j].key
	})

	// Flush with sequential indices.
	for i, entry := range ctx.mapBuf {
		newPrefix := s.currPrefix + "." + strconv.Itoa(i+1)
		for _, kv := range entry.values {
			s.writeValue(newPrefix+kv.k, kv.v)
		}
	}

	s.currPrefix = ctx.prefix
}

// WriteBigInteger is unimplemented.
func (s *ShapeSerializer) WriteBigInteger(_ *smithy.Schema, _ big.Int) {
	panic("query: BigInteger not supported")
}

// WriteBigDecimal is unimplemented.
func (s *ShapeSerializer) WriteBigDecimal(_ *smithy.Schema, _ big.Float) {
	panic("query: BigDecimal not supported")
}

// WriteDocument is unimplemented.
func (s *ShapeSerializer) WriteDocument(_ *smithy.Schema, _ document.Value) {
	panic("query: Document not supported")
}

func (s *ShapeSerializer) consumeMapValue() string {
	ctx := s.pop()
	valPrefix := s.currPrefix
	s.currPrefix = ctx.prefix
	return valPrefix
}

func ec2MemberName(schema *smithy.Schema) string {
	if t, ok := smithy.SchemaTrait[*traits.EC2QueryName](schema); ok {
		return t.Name
	}
	if xn, ok := smithy.SchemaTrait[*traits.XMLName](schema); ok {
		return capitalize(xn.Name)
	}
	return capitalize(schema.MemberName())
}

func capitalize(s string) string {
	if s == "" || s[0] >= 'A' && s[0] <= 'Z' {
		return s
	}

	return string(s[0]-'a'+'A') + s[1:]
}
