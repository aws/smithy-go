package xml

import (
	"bytes"
	"encoding/base64"
	"encoding/xml"
	"fmt"
	"io"
	"math"
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
	ctxKindStruct ctxKind = iota
	ctxKindList
	ctxKindMap
)

type deserCtx struct {
	kind      ctxKind
	schema    *smithy.Schema
	flattened bool

	// for flattened list/map, ReadStructMember consumes the start element
	// when it discovers the member, subsequent calls to ReadMapKey or
	// ReadListItem will do that instead
	first bool

	// for map, ReadX after ReadMapKey will leave the stream at </value>
	// which the next call to ReadMapKey must consume
	inEntry bool
}

// ShapeDeserializer implements unmarshaling of XML into Smithy shapes.
//
// ShapeDeserializer expects the **inner XML** of the protocol response that
// represents the operation output. For example, an awsquery response body
// looks like this:
//
//	<[OperationName]Response>
//	    <[OperationName]Result>
//	        <Member1>...</Member1>
//	        <Member2>...</Member2>
//	        ...
//	        <MemberN>...</MemberN>
//	    </[OperationName]Result>
//	    <ResponseMetadata>
//	        ...
//	    </ResponseMetadata>
//	</[OperationName]Response>
//
// The deserializer must receive "<Member1>...</MemberN>" to operate correctly.
type ShapeDeserializer struct {
	dec    *xml.Decoder
	peeked xml.Token
	stack  []deserCtx
}

var _ smithy.ShapeDeserializer = (*ShapeDeserializer)(nil)

// NewShapeDeseralizer returns a new ShapeDeserializer.
func NewShapeDeserializer(p []byte) *ShapeDeserializer {
	return &ShapeDeserializer{
		dec: xml.NewDecoder(bytes.NewReader(p)),
	}
}

func (d *ShapeDeserializer) push(ctx deserCtx) {
	d.stack = append(d.stack, ctx)
}

func (d *ShapeDeserializer) pop() deserCtx {
	n := len(d.stack)
	v := d.stack[n-1]
	d.stack = d.stack[:n-1]
	return v
}

func (d *ShapeDeserializer) top() *deserCtx {
	if len(d.stack) == 0 {
		return nil
	}
	return &d.stack[len(d.stack)-1]
}

func xmlMemberName(schema *smithy.Schema) string {
	if t, ok := smithy.SchemaTrait[*traits.XMLName](schema); ok {
		return t.Name
	}
	return schema.MemberName()
}

func findMember(schema *smithy.Schema, elemName string) *smithy.Schema {
	if schema == nil {
		return nil
	}

	for _, m := range schema.Members() {
		mName := xmlMemberName(m)
		if strings.EqualFold(mName, elemName) {
			return m
		}
	}
	return nil
}

// ReadStruct implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadStruct(s *smithy.Schema) error {
	d.push(deserCtx{kind: ctxKindStruct, schema: s})
	return nil
}

// ReadStructMember implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadStructMember() (*smithy.Schema, error) {
	ctx := d.top()
	if ctx == nil || ctx.kind != ctxKindStruct {
		return nil, fmt.Errorf("ReadStructMember called without ReadStruct")
	}

	for {
		start, ok, err := d.nextStart()
		if err != nil {
			// on the top-level struct we are guaranteed to get an EOF since
			// we're operating on inner XML
			if err == io.EOF && len(d.stack) == 1 {
				d.pop()
				return nil, nil
			}

			return nil, err
		}
		if !ok {
			d.pop()
			return nil, nil
		}

		member := findMember(ctx.schema, start.Name.Local)
		if member == nil {
			if err := d.skip(); err != nil {
				return nil, err
			}
			continue
		}

		return member, nil
	}
}

// ReadList implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadList(s *smithy.Schema) error {
	_, flattened := smithy.SchemaTrait[*traits.XMLFlattened](s)
	d.push(deserCtx{
		kind:      ctxKindList,
		schema:    s,
		flattened: flattened,
		first:     flattened,
	})
	return nil
}

// ReadListItem implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadListItem(_ *smithy.Schema) (bool, error) {
	ctx := d.top()
	if ctx.flattened {
		return d.readFlatListItem()
	}
	return d.readWrappedListItem()
}

func (d *ShapeDeserializer) readWrappedListItem() (bool, error) {
	// the old version used WrapNodeDecoder on each item and didn't check its
	// name (or for xmlName) so we're ignoring it too
	_, ok, err := d.nextStart()
	if err != nil {
		return false, err
	}
	if !ok {
		d.pop()
		return false, nil
	}

	return true, nil
}

func (d *ShapeDeserializer) readFlatListItem() (bool, error) {
	ctx := d.top()
	if ctx.first {
		ctx.first = false
		return true, nil
	}

	expectName := xmlMemberName(ctx.schema)

	for {
		tok, err := d.token()
		if err != nil {
			return false, err
		}

		switch t := tok.(type) {
		case xml.StartElement:
			if strings.EqualFold(t.Name.Local, expectName) {
				return true, nil
			}

			d.peeked = t
			d.pop()
			return false, nil
		case xml.EndElement:
			d.peeked = t
			d.pop()
			return false, nil
		}
	}
}

// ReadMap implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadMap(s *smithy.Schema) error {
	_, flattened := smithy.SchemaTrait[*traits.XMLFlattened](s)
	d.push(deserCtx{
		kind:      ctxKindMap,
		schema:    s,
		flattened: flattened,
		first:     flattened,
	})
	return nil
}

// ReadMapKey implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadMapKey(ks *smithy.Schema) (string, bool, error) {
	ctx := d.top()
	if ctx.inEntry {
		ctx.inEntry = false
		if _, _, err := d.nextStart(); err != nil {
			return "", false, err
		}
	}

	vs := ctx.schema.MapValue()

	if ctx.flattened {
		return d.readFlatMapKey(ks, vs)
	}

	return d.readWrappedMapKey(ks, vs)
}

func (d *ShapeDeserializer) readWrappedMapKey(ks, vs *smithy.Schema) (string, bool, error) {
	for {
		start, a, err := d.nextStart()
		if err != nil {
			return "", false, err
		}
		if !a {
			d.pop()
			return "", false, nil
		}

		// unlike lists the old codegen actually DID check that the element was
		// named "entry", skipping if it wasn't
		if strings.EqualFold(start.Name.Local, "entry") {
			return d.readEntry(ks, vs)
		}

		if err := d.skip(); err != nil {
			return "", false, err
		}
	}
}

func (d *ShapeDeserializer) readFlatMapKey(ks, vs *smithy.Schema) (string, bool, error) {
	ctx := d.top()
	if ctx.first {
		ctx.first = false
		return d.readEntry(ks, vs)
	}

	expectName := xmlMemberName(ctx.schema)
	for {
		tok, err := d.token()
		if err != nil {
			return "", false, err
		}

		switch t := tok.(type) {
		case xml.StartElement:
			if strings.EqualFold(t.Name.Local, expectName) {
				return d.readEntry(ks, vs)
			}

			d.peeked = t
			d.pop()
			return "", false, nil
		case xml.EndElement:
			d.peeked = t
			d.pop()
			return "", false, nil
		}
	}
}

func (d *ShapeDeserializer) readEntry(ks, vs *smithy.Schema) (string, bool, error) {
	ctx := d.top()

	kname := "key"
	if xn, ok := smithy.SchemaTrait[*traits.XMLName](ks); ok {
		kname = xn.Name
	}

	vname := "value"
	if xn, ok := smithy.SchemaTrait[*traits.XMLName](vs); ok {
		vname = xn.Name
	}

	var key string
	for {
		child, found, err := d.nextStart()
		if err != nil {
			return "", false, err
		}
		if !found {
			break
		}

		switch {
		case strings.EqualFold(child.Name.Local, kname):
			key, err = d.chardata()
			if err != nil {
				return "", false, err
			}
		case strings.EqualFold(child.Name.Local, vname):
			ctx.inEntry = true
			return key, true, nil
		default:
			if err := d.skip(); err != nil {
				return "", false, err
			}
		}
	}

	return key, true, nil
}

// ReadNil implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadNil(_ *smithy.Schema) (bool, error) {
	return false, nil
}

// ReadBool implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadBool(_ *smithy.Schema, v *bool) error {
	text, err := d.chardata()
	if err != nil {
		return err
	}

	b, err := strconv.ParseBool(text)
	if err != nil {
		return fmt.Errorf("parse bool %q: %w", text, err)
	}

	*v = b
	return nil
}

// ReadBoolPtr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadBoolPtr(s *smithy.Schema, v **bool) error {
	return readPtr(d, s, v, d.ReadBool)
}

// ReadInt8 implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadInt8(s *smithy.Schema, v *int8) error {
	n, err := d.readInt(8)
	if err != nil {
		return err
	}

	*v = int8(n)
	return nil
}

// ReadInt16 implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadInt16(s *smithy.Schema, v *int16) error {
	n, err := d.readInt(16)
	if err != nil {
		return err
	}

	*v = int16(n)
	return nil
}

// ReadInt32 implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadInt32(s *smithy.Schema, v *int32) error {
	n, err := d.readInt(32)
	if err != nil {
		return err
	}

	*v = int32(n)
	return nil
}

// ReadInt64 implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadInt64(s *smithy.Schema, v *int64) error {
	n, err := d.readInt(64)
	if err != nil {
		return err
	}

	*v = n
	return nil
}

// ReadInt8Ptr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadInt8Ptr(s *smithy.Schema, v **int8) error {
	return readPtr(d, s, v, d.ReadInt8)
}

// ReadInt16Ptr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadInt16Ptr(s *smithy.Schema, v **int16) error {
	return readPtr(d, s, v, d.ReadInt16)
}

// ReadInt32Ptr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadInt32Ptr(s *smithy.Schema, v **int32) error {
	return readPtr(d, s, v, d.ReadInt32)
}

// ReadInt64Ptr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadInt64Ptr(s *smithy.Schema, v **int64) error {
	return readPtr(d, s, v, d.ReadInt64)
}

// ReadFloat32 implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadFloat32(s *smithy.Schema, v *float32) error {
	n, err := d.readFloat()
	if err != nil {
		return err
	}

	*v = float32(n)
	return nil
}

// ReadFloat64 implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadFloat64(s *smithy.Schema, v *float64) error {
	n, err := d.readFloat()
	if err != nil {
		return err
	}

	*v = n
	return nil
}

// ReadFloat32Ptr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadFloat32Ptr(s *smithy.Schema, v **float32) error {
	return readPtr(d, s, v, d.ReadFloat32)
}

// ReadFloat64Ptr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadFloat64Ptr(s *smithy.Schema, v **float64) error {
	return readPtr(d, s, v, d.ReadFloat64)
}

// ReadString implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadString(_ *smithy.Schema, v *string) error {
	text, err := d.chardata()
	if err != nil {
		return err
	}

	*v = text
	return nil
}

// ReadStringPtr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadStringPtr(s *smithy.Schema, v **string) error {
	return readPtr(d, s, v, d.ReadString)
}

// ReadTime implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadTime(schema *smithy.Schema, v *time.Time) error {
	format := "date-time"
	if t, ok := smithy.SchemaTrait[*traits.TimestampFormat](schema); ok {
		format = t.Format
	}

	text, err := d.chardata()
	if err != nil {
		return err
	}

	switch format {
	case "date-time":
		t, err := smithytime.ParseDateTime(text)
		if err != nil {
			return err
		}
		*v = t
	case "http-date":
		t, err := smithytime.ParseHTTPDate(text)
		if err != nil {
			return err
		}
		*v = t
	case "epoch-seconds":
		n, err := strconv.ParseFloat(text, 64)
		if err != nil {
			return err
		}
		*v = smithytime.ParseEpochSeconds(n)
	default:
		return fmt.Errorf("unknown timestamp format: %s", format)
	}

	return nil
}

// ReadTimePtr implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadTimePtr(schema *smithy.Schema, v **time.Time) error {
	return readPtr(d, schema, v, d.ReadTime)
}

// ReadBlob implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadBlob(s *smithy.Schema, v *[]byte) error {
	text, err := d.chardata()
	if err != nil {
		return err
	}

	if text == "" {
		return nil
	}

	b, err := base64.StdEncoding.DecodeString(text)
	if err != nil {
		return fmt.Errorf("decode base64 blob: %w", err)
	}
	*v = b
	return nil
}

// ReadUnion implements [smithy.ShapeDeserializer].
func (d *ShapeDeserializer) ReadUnion(s *smithy.Schema) (*smithy.Schema, error) {
	start, ok, err := d.nextStart()
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, nil
	}

	member := findMember(s, start.Name.Local)
	if member == nil {
		if err := d.skip(); err != nil {
			return nil, err
		}
		return d.ReadUnion(s)
	}

	return member, nil
}

// ReadDocument is unimplemented for XML.
func (d *ShapeDeserializer) ReadDocument(_ *smithy.Schema, _ *document.Value) error {
	return fmt.Errorf("Document not supported")
}

func readPtr[T any](d *ShapeDeserializer, s *smithy.Schema, v **T, read func(*smithy.Schema, *T) error) error {
	if *v == nil {
		*v = new(T)
	}
	return read(s, *v)
}

func (d *ShapeDeserializer) token() (xml.Token, error) {
	if d.peeked != nil {
		tok := d.peeked
		d.peeked = nil
		return tok, nil
	}

	return d.dec.Token()
}

func (d *ShapeDeserializer) chardata() (string, error) {
	// a single "inner XML" node can be multiple xml.CharData so we need to
	// accumulate them
	var buf strings.Builder

	for {
		tok, err := d.token()
		if err != nil {
			return "", err
		}

		switch t := tok.(type) {
		case xml.CharData:
			buf.Write(t)

		// IMPORTANT: also consumes the closing tag AFTER the chardata, so
		// future ReadWhatevers don't have to think about that
		case xml.EndElement:
			return buf.String(), nil

		default:
			return "", fmt.Errorf("unexpected token %T", tok)
		}
	}
}

// there is xml.Decoder.Skip but this is a special case to assume we already
// consumed the start element and to handle any peeked tokens
func (d *ShapeDeserializer) skip() error {
	depth := 1
	for depth > 0 {
		tok, err := d.token()
		if err != nil {
			return err
		}

		switch tok.(type) {
		case xml.StartElement:
			depth++
		case xml.EndElement:
			depth--
		}
	}

	return nil
}

func (d *ShapeDeserializer) nextStart() (xml.StartElement, bool, error) {
	for {
		tok, err := d.token()
		if err != nil {
			return xml.StartElement{}, false, err
		}

		switch t := tok.(type) {
		case xml.StartElement:
			return t, true, nil
		case xml.EndElement: // ie. the end of the struct/list/map
			return xml.StartElement{}, false, nil
		default:
			continue
		}
	}
}

func (d *ShapeDeserializer) readInt(bits int) (int64, error) {
	text, err := d.chardata()
	if err != nil {
		return 0, err
	}

	return strconv.ParseInt(text, 10, bits)
}

func (d *ShapeDeserializer) readFloat() (float64, error) {
	text, err := d.chardata()
	if err != nil {
		return 0, err
	}

	switch {
	case strings.EqualFold(text, "NaN"):
		return math.NaN(), nil
	case strings.EqualFold(text, "Infinity"):
		return math.Inf(1), nil
	case strings.EqualFold(text, "-Infinity"):
		return math.Inf(-1), nil
	default:
		return strconv.ParseFloat(text, 64)
	}
}
