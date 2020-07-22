package xml_test

import (
	"bytes"
	"testing"

	"github.com/awslabs/smithy-go/xml"
)

var root = xml.StartElement{Name: xml.Name{Local: "root"}}

func TestEncoder(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		root := encoder.RootElement(root)
		defer root.Close()

		stringKey := xml.StartElement{Name: xml.Name{Local: "stringKey"}}
		integerKey := xml.StartElement{Name: xml.Name{Local: "integerKey"}}
		floatKey := xml.StartElement{Name: xml.Name{Local: "floatKey"}}
		foo := xml.StartElement{Name: xml.Name{Local: "foo"}}
		byteSlice := xml.StartElement{Name: xml.Name{Local: "byteSlice"}}

		root.MemberElement(stringKey).String("stringValue")
		root.MemberElement(integerKey).Integer(1024)
		root.MemberElement(floatKey).Float(3.14)

		ns := root.MemberElement(foo)
		defer ns.Close()
		ns.MemberElement(byteSlice).String("Zm9vIGJhcg==")
	}()

	e := []byte(`<root><stringKey>stringValue</stringKey><integerKey>1024</integerKey><floatKey>3.14</floatKey><foo><byteSlice>Zm9vIGJhcg==</byteSlice></foo></root>`)
	verify(t, encoder, e)
}

func TestEncodeAttribute(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := xml.StartElement{
			Name: xml.Name{Local: "payload", Space: "baz"},
			Attr: []xml.Attr{
				xml.NewAttribute("attrkey", "value"),
			},
		}

		obj := encoder.RootElement(r)
		obj.String("")
	}()

	expect := `<baz:payload attrkey="value"></baz:payload>`

	verify(t, encoder, []byte(expect))
}

func TestEncodeNamespace(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		root := encoder.RootElement(root)
		defer root.Close()

		key := xml.StartElement{
			Name: xml.Name{Local: "namespace"},
			Attr: []xml.Attr{
				xml.NewNamespaceAttribute("prefix", "https://example.com"),
			},
		}

		n := root.MemberElement(key)
		defer n.Close()

		prefix := xml.StartElement{Name: xml.Name{Local: "user"}}
		n.MemberElement(prefix).String("abc")
	}()

	e := []byte(`<root><namespace xmlns:prefix="https://example.com"><user>abc</user></namespace></root>`)
	verify(t, encoder, e)
}

func TestEncodeEmptyNamespacePrefix(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)
	func() {
		root := encoder.RootElement(root)
		defer root.Close()

		key := xml.StartElement{
			Name: xml.Name{Local: "namespace"},
			Attr: []xml.Attr{
				xml.NewNamespaceAttribute("", "https://example.com"),
			},
		}

		n := root.MemberElement(key)
		defer n.Close()

		prefix := xml.StartElement{Name: xml.Name{Local: "user"}}
		n.MemberElement(prefix).String("abc")
	}()

	e := []byte(`<root><namespace xmlns="https://example.com"><user>abc</user></namespace></root>`)
	verify(t, encoder, e)
}

func verify(t *testing.T, encoder *xml.Encoder, e []byte) {
	if a := encoder.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}

	if a := encoder.String(); string(encoder.Bytes()) != a {
		t.Errorf("expected %s, but got %s", e, a)
	}
}

func TestEncodeNestedShape(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement(root)
		defer r.Close()

		// nested `nested` shape
		nested := xml.StartElement{Name: xml.Name{Local: "nested"}}
		n1 := r.MemberElement(nested)
		defer n1.Close()

		// nested `value` shape
		value := xml.StartElement{Name: xml.Name{Local: "value"}}
		n1.MemberElement(value).String("expected value")
	}()

	e := []byte(`<root><nested><value>expected value</value></nested></root>`)
	defer verify(t, encoder, e)
}

func TestEncodeMapString(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement(root)
		defer r.Close()

		// nested `mapStr` shape
		mapstr := xml.StartElement{Name: xml.Name{Local: "mapstr"}}
		m := r.CollectionElement(mapstr).Map()
		defer m.Close()

		key := xml.StartElement{Name: xml.Name{Local: "key"}}
		value := xml.StartElement{Name: xml.Name{Local: "value"}}

		e := m.Entry()
		defer e.Close()
		e.MemberElement(key).String("abc")
		e.MemberElement(value).Integer(123)
	}()

	ex := []byte(`<root><mapstr><entry><key>abc</key><value>123</value></entry></mapstr></root>`)
	verify(t, encoder, ex)
}

func TestEncodeMapFlatten(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement(root)
		defer r.Close()
		// nested `mapStr` shape
		mapstr := xml.StartElement{Name: xml.Name{Local: "mapstr"}}
		m := r.CollectionElement(mapstr).FlattenedMap()
		defer m.Close()

		key := xml.StartElement{Name: xml.Name{Local: "key"}}
		value := xml.StartElement{Name: xml.Name{Local: "value"}}

		e := m.Entry()
		e.MemberElement(key).String("abc")
		e.MemberElement(value).Integer(123)
		defer e.Close()

	}()

	ex := []byte(`<root><mapstr><key>abc</key><value>123</value></mapstr></root>`)
	verify(t, encoder, ex)
}

func TestEncodeMapNamed(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement(root)
		defer r.Close()
		// nested `mapStr` shape
		mapstr := xml.StartElement{Name: xml.Name{Local: "mapNamed"}}
		m := r.CollectionElement(mapstr).Map()
		defer m.Close()

		key := xml.StartElement{Name: xml.Name{Local: "namedKey"}}
		value := xml.StartElement{Name: xml.Name{Local: "namedValue"}}

		e := m.Entry()
		e.MemberElement(key).String("abc")
		e.MemberElement(value).Integer(123)
		defer e.Close()
	}()

	ex := []byte(`<root><mapNamed><entry><namedKey>abc</namedKey><namedValue>123</namedValue></entry></mapNamed></root>`)
	verify(t, encoder, ex)
}

func TestEncodeMapShape(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement(root)
		defer r.Close()
		// nested `mapStr` shape
		mapstr := xml.StartElement{Name: xml.Name{Local: "mapShape"}}
		m := r.CollectionElement(mapstr).Map()
		defer m.Close()

		key := xml.StartElement{Name: xml.Name{Local: "key"}}
		value := xml.StartElement{Name: xml.Name{Local: "value"}}

		e := m.Entry()
		defer e.Close()
		e.MemberElement(key).String("abc")
		n1 := e.MemberElement(value)
		defer n1.Close()

		shapeVal := xml.StartElement{Name: xml.Name{Local: "shapeVal"}}
		n1.MemberElement(shapeVal).Integer(1)
	}()

	ex := []byte(`<root><mapShape><entry><key>abc</key><value><shapeVal>1</shapeVal></value></entry></mapShape></root>`)
	verify(t, encoder, ex)
}

func TestEncodeMapFlattenShape(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement(root)
		defer r.Close()
		// nested `mapStr` shape
		mapstr := xml.StartElement{Name: xml.Name{Local: "mapShape"}}
		m := r.CollectionElement(mapstr).FlattenedMap()
		defer m.Close()

		key := xml.StartElement{Name: xml.Name{Local: "key"}}
		value := xml.StartElement{Name: xml.Name{Local: "value"}}

		e := m.Entry()
		defer e.Close()
		e.MemberElement(key).String("abc")
		n1 := e.MemberElement(value)
		defer n1.Close()

		shapeVal := xml.StartElement{Name: xml.Name{Local: "shapeVal"}}
		n1.MemberElement(shapeVal).Integer(1)
	}()
	ex := []byte(`<root><mapShape><key>abc</key><value><shapeVal>1</shapeVal></value></mapShape></root>`)
	verify(t, encoder, ex)
}

func TestEncodeMapNamedShape(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement(root)
		defer r.Close()
		// nested `mapStr` shape
		mapstr := xml.StartElement{Name: xml.Name{Local: "mapNamedShape"}}
		m := r.CollectionElement(mapstr).Map()
		defer m.Close()

		key := xml.StartElement{Name: xml.Name{Local: "namedKey"}}
		value := xml.StartElement{Name: xml.Name{Local: "namedValue"}}

		e := m.Entry()
		defer e.Close()
		e.MemberElement(key).String("abc")
		n1 := e.MemberElement(value)
		defer n1.Close()

		shapeVal := xml.StartElement{Name: xml.Name{Local: "shapeVal"}}
		n1.MemberElement(shapeVal).Integer(1)
	}()

	ex := []byte(`<root><mapNamedShape><entry><namedKey>abc</namedKey><namedValue><shapeVal>1</shapeVal></namedValue></entry></mapNamedShape></root>`)
	verify(t, encoder, ex)
}

func TestEncodeListString(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement(root)
		defer r.Close()

		// Object key `liststr`
		liststr := xml.StartElement{Name: xml.Name{Local: "liststr"}}
		a := r.CollectionElement(liststr).Array()
		defer a.Close()

		a.Member().String("abc")
		a.Member().Integer(123)
	}()

	ex := []byte(`<root><liststr><member>abc</member><member>123</member></liststr></root>`)
	verify(t, encoder, ex)
}

func TestEncodeListFlatten(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement(root)
		defer r.Close()

		// Object key `liststr`
		liststr := xml.StartElement{Name: xml.Name{Local: "liststr"}}
		a := r.CollectionElement(liststr).FlattenedArray()
		defer a.Close()

		a.Member().String("abc")
		a.Member().Integer(123)
	}()

	ex := []byte(`<root><liststr>abc</liststr><liststr>123</liststr></root>`)
	verify(t, encoder, ex)
}

func TestEncodeListNamed(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement(root)
		defer r.Close()

		// Object key `liststr`
		liststr := xml.StartElement{Name: xml.Name{Local: "liststr"}}

		namedMember := xml.StartElement{Name: xml.Name{Local: "namedMember"}}
		a := r.CollectionElement(liststr).ArrayWithCustomName(namedMember)
		defer a.Close()

		a.Member().String("abc")
		a.Member().Integer(123)
	}()

	ex := []byte(`<root><liststr><namedMember>abc</namedMember><namedMember>123</namedMember></liststr></root>`)
	verify(t, encoder, ex)
}

func TestEncodeListShape(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement(root)
		defer r.Close()

		// Object key `liststr`
		liststr := xml.StartElement{Name: xml.Name{Local: "liststr"}}

		a := r.CollectionElement(liststr).Array()
		defer a.Close()

		value := xml.StartElement{Name: xml.Name{Local: "value"}}

		m1 := a.Member()
		m1.MemberElement(value).String("abc")
		m1.Close()

		m2 := a.Member()
		m2.MemberElement(value).Integer(123)
		m2.Close()
	}()

	ex := []byte(`<root><liststr><member><value>abc</value></member><member><value>123</value></member></liststr></root>`)
	verify(t, encoder, ex)
}

func TestEncodeListFlattenShape(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement(root)
		defer r.Close()

		// Object key `liststr`
		liststr := xml.StartElement{Name: xml.Name{Local: "liststr"}}

		a := r.CollectionElement(liststr).FlattenedArray()
		defer a.Close()

		value := xml.StartElement{Name: xml.Name{Local: "value"}}

		m1 := a.Member()
		m1.MemberElement(value).String("abc")
		m1.Close()

		m2 := a.Member()
		m2.MemberElement(value).Integer(123)
		m2.Close()
	}()

	ex := []byte(`<root><liststr><value>abc</value></liststr><liststr><value>123</value></liststr></root>`)
	verify(t, encoder, ex)
}

func TestEncodeListNamedShape(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement(root)
		defer r.Close()

		// Object key `liststr`
		liststr := xml.StartElement{Name: xml.Name{Local: "liststr"}}
		namedMember := xml.StartElement{Name: xml.Name{Local: "namedMember"}}
		a := r.CollectionElement(liststr).ArrayWithCustomName(namedMember)
		defer a.Close()

		value := xml.StartElement{Name: xml.Name{Local: "value"}}

		m1 := a.Member()
		m1.MemberElement(value).String("abc")
		m1.Close()

		m2 := a.Member()
		m2.MemberElement(value).Integer(123)
		m2.Close()
	}()

	ex := []byte(`<root><liststr><namedMember><value>abc</value></namedMember><namedMember><value>123</value></namedMember></liststr></root>`)
	verify(t, encoder, ex)
}