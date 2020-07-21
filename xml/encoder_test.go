package xml_test

import (
	"bytes"
	"testing"

	"github.com/awslabs/smithy-go/xml"
)

func TestEncoder(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		root := encoder.RootElement("Root", nil)
		obj := root.NestedElement()

		obj.Key("stringKey", nil).String("stringValue")
		obj.Key("integerKey", nil).Integer(1024)
		obj.Key("floatKey", nil).Float(3.14)
		defer obj.Close()

		vk := obj.Key("foo", nil).NestedElement()
		vk.Key("byteSlice", nil).String("Zm9vIGJhcg==")
		defer vk.Close()
	}()

	e := []byte(`<Root><stringKey>stringValue</stringKey><integerKey>1024</integerKey><floatKey>3.14</floatKey><foo><byteSlice>Zm9vIGJhcg==</byteSlice></foo></Root>`)
	verify(t, encoder, e)
}

func TestEncodeAttribute(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	obj := encoder.RootElement("payload", &[]xml.Attr{
		*xml.NewAttribute("attrkey", "value"),
	})
	obj.Null()

	expect := `<payload attrkey="value"></payload>`

	verify(t, encoder, []byte(expect))
}

func TestEncodeNamespace(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)
	root := encoder.RootElement("payload", nil)

	func() {
		n := root.NestedElement()
		defer n.Close()

		// nested `namespace` shape
		n1 := n.Key("namespace", &[]xml.Attr{
			*xml.NewNamespaceAttribute("prefix", "https://example.com"),
		}).NestedElement()
		defer n1.Close()

		n1.Key("prefixed", nil).String("abc")
	}()

	e := []byte(`<payload><namespace xmlns:prefix="https://example.com"><prefixed>abc</prefixed></namespace></payload>`)
	verify(t, encoder, e)
}

func TestEncodeEmptyNamespacePrefix(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)
	root := encoder.RootElement("payload", nil)

	func() {
		n := root.NestedElement()
		defer n.Close()

		// nested `namespace` shape
		n1 := n.Key("namespace", &[]xml.Attr{
			*xml.NewNamespaceAttribute("", "https://example.com"),
		}).NestedElement()
		defer n1.Close()

		n1.Key("prefixed", nil).String("abc")
	}()

	e := []byte(`<payload><namespace xmlns="https://example.com"><prefixed>abc</prefixed></namespace></payload>`)
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
		o := encoder.RootElement("payload", nil)

		// nested `nested` shape
		n1 := o.NestedElement()
		defer n1.Close()

		v := n1.Key("nested", nil)
		// nested `value` shape
		n2 := v.NestedElement()
		n2.Key("value", nil).String("expected value")
		defer n2.Close()
	}()

	e := []byte(`<payload><nested><value>expected value</value></nested></payload>`)
	defer verify(t, encoder, e)
}

func TestEncodeMapString(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		o := encoder.RootElement("payload", nil)

		// nested `mapStr` shape
		n1 := o.NestedElement()
		defer n1.Close()

		m := n1.Key("mapstr", nil).Map()
		defer m.Close()

		e := m.Entry()
		e.Key("key", nil).String("abc")
		e.Key("value", nil).Integer(123)
		defer e.Close()
	}()

	ex := []byte(`<payload><mapstr><entry><key>abc</key><value>123</value></entry></mapstr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapFlatten(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)
	o := encoder.RootElement("payload", nil)

	func() {
		// nested `mapStr` shape
		n1 := o.NestedElement()
		defer n1.Close()

		m := n1.Key("mapstr", nil).FlattenedMap()
		defer m.Close()

		e := m.Entry()
		e.Key("key", nil).String("abc")
		e.Key("value", nil).Integer(123)
		defer e.Close()

	}()

	ex := []byte(`<payload><mapstr><key>abc</key><value>123</value></mapstr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapNamed(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)
	o := encoder.RootElement("payload", nil)

	func() {
		// nested `mapStr` shape
		n1 := o.NestedElement()
		defer n1.Close()

		m := n1.Key("mapNamed", nil).Map()
		defer m.Close()

		// entry
		e := m.Entry()
		e.Key("namedKey", nil).String("abc")
		e.Key("namedValue", nil).Integer(123)
		defer e.Close()
	}()

	ex := []byte(`<payload><mapNamed><entry><namedKey>abc</namedKey><namedValue>123</namedValue></entry></mapNamed></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapShape(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		o := encoder.RootElement("payload", nil)

		// nested `mapStr` shape
		n1 := o.NestedElement()
		defer n1.Close()

		m := n1.Key("mapShape", nil).Map()
		defer m.Close()

		e := m.Entry()
		defer e.Close()

		e.Key("key", nil).String("abc")
		n2 := e.Key("value", nil).NestedElement()
		defer n2.Close()
		n2.Key("shapeVal", nil).Integer(1)
	}()

	ex := []byte(`<payload><mapShape><entry><key>abc</key><value><shapeVal>1</shapeVal></value></entry></mapShape></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapFlattenShape(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		root := encoder.RootElement("payload", nil)

		// nested `mapStr` shape
		n1 := root.NestedElement()
		defer n1.Close()

		m := n1.Key("mapShape", nil).FlattenedMap()
		defer m.Close()

		e := m.Entry()
		defer e.Close()

		e.Key("key", nil).String("abc")
		n2 := e.Key("value", nil).NestedElement()
		defer n2.Close()
		n2.Key("shapeVal", nil).Integer(1)
	}()

	ex := []byte(`<payload><mapShape><key>abc</key><value><shapeVal>1</shapeVal></value></mapShape></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapNamedShape(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		root := encoder.RootElement("payload", nil)

		// nested `mapStr` shape
		n1 := root.NestedElement()
		defer n1.Close()

		m := n1.Key("mapNamedShape", nil).Map()
		defer m.Close()

		e := m.Entry()
		defer e.Close()

		e.Key("namedKey", nil).String("abc")
		n2 := e.Key("namedValue", nil).NestedElement()
		defer n2.Close()
		n2.Key("shapeVal", nil).Integer(1)
	}()

	ex := []byte(`<payload><mapNamedShape><entry><namedKey>abc</namedKey><namedValue><shapeVal>1</shapeVal></namedValue></entry></mapNamedShape></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListString(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement("payload", nil)

		// Object key `liststr`
		n1 := r.NestedElement()
		defer n1.Close()

		a := n1.Key("liststr", nil).Array()
		defer a.Close()

		a.Member().String("abc")
		a.Member().Integer(123)
	}()

	ex := []byte(`<payload><liststr><member>abc</member><member>123</member></liststr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListFlatten(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement("payload", nil)

		// Object key `liststr`
		n1 := r.NestedElement()
		defer n1.Close()

		a := n1.Key("liststr", nil).FlattenedArray()
		defer a.Close()

		a.Member().String("abc")
		a.Member().Integer(123)
	}()

	ex := []byte(`<payload><liststr>abc</liststr><liststr>123</liststr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListNamed(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement("payload", nil)

		// Object key `liststr`
		n1 := r.NestedElement()
		defer n1.Close()

		a := n1.Key("liststr", nil).ArrayWithCustomName("namedMember")
		defer a.Close()

		a.Member().String("abc")
		a.Member().Integer(123)
	}()
	ex := []byte(`<payload><liststr><namedMember>abc</namedMember><namedMember>123</namedMember></liststr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListShape(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement("payload", nil)

		// Object key `liststr`
		n1 := r.NestedElement()
		defer n1.Close()

		a := n1.Key("liststr", nil).Array()
		defer a.Close()

		// build entry
		n2 := a.Member().NestedElement()
		n2.Key("value", nil).String("abc")
		n2.Close()

		// build next entry
		n3 := a.Member().NestedElement()
		n3.Key("value", nil).Integer(123)
		n3.Close()
	}()

	ex := []byte(`<payload><liststr><member><value>abc</value></member><member><value>123</value></member></liststr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListFlattenShape(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement("payload", nil)

		// Object key `liststr`
		n1 := r.NestedElement()
		defer n1.Close()

		a := n1.Key("liststr", nil).FlattenedArray()
		defer a.Close()
		// build entry
		n2 := a.Member().NestedElement()
		n2.Key("value", nil).String("abc")
		n2.Close()

		// build next entry
		n3 := a.Member().NestedElement()
		n3.Key("value", nil).Integer(123)
		n3.Close()
	}()

	ex := []byte(`<payload><liststr><value>abc</value></liststr><liststr><value>123</value></liststr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListNamedShape(t *testing.T) {
	b := bytes.NewBuffer(nil)
	encoder := xml.NewEncoder(b)

	func() {
		r := encoder.RootElement("payload", nil)

		// Object key `liststr`
		n1 := r.NestedElement()
		defer n1.Close()

		a := n1.Key("liststr", nil).ArrayWithCustomName("namedMember")
		defer a.Close()

		// build entry
		n2 := a.Member().NestedElement()
		n2.Key("value", nil).String("abc")
		n2.Close()

		// build next entry
		n3 := a.Member().NestedElement()
		n3.Key("value", nil).Integer(123)
		n3.Close()
	}()

	ex := []byte(`<payload><liststr><namedMember><value>abc</value></namedMember><namedMember><value>123</value></namedMember></liststr></payload>`)
	verify(t, encoder, ex)
}
