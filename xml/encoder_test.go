package xml_test

import (
	"bytes"
	"testing"

	"github.com/awslabs/smithy-go/xml"
)

func TestEncoder(t *testing.T) {
	encoder := xml.NewEncoder()

	root := encoder.RootElement("Root", nil)
	obj := root.NestedElement()

	obj.Key("stringKey", nil).String("stringValue")
	obj.Key("integerKey", nil).Integer(1024)
	obj.Key("floatKey", nil).Float(3.14)

	vk := obj.Key("foo", nil).NestedElement()
	vk.Key("byteSlice", nil).String("Zm9vIGJhcg==")

	vk.Close()
	obj.Close()

	e := []byte(`<Root><stringKey>stringValue</stringKey><integerKey>1024</integerKey><floatKey>3.14</floatKey><foo><byteSlice>Zm9vIGJhcg==</byteSlice></foo></Root>`)
	if a := encoder.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}

	if a := encoder.String(); string(encoder.Bytes()) != a {
		t.Errorf("expected %s, but got %s", e, a)
	}
}

func TestEncodeAttribute(t *testing.T) {
	encoder := xml.NewEncoder()

	obj := encoder.RootElement("payload", &[]xml.Attr{{Name: xml.Name{Local: "attrkey"}, Value: "value"}})
	obj.Null()

	expect := `<payload attrkey="value"></payload>`

	if e, a := expect, encoder.String(); e != a {
		t.Errorf("expect bodies to match, did not.\n,\tExpect:\n%s\n\tActual:\n%s\n", e, a)
	}
}

func TestEncodeNamespace(t *testing.T) {
	encoder := xml.NewEncoder()
	root := encoder.RootElement("payload", nil)

	n := root.NestedElement()

	// nested `namespace` shape
	n1 := n.Key("namespace", &[]xml.Attr{
		{
			Name: xml.Name{
				Local: "prefix",
				Space: "xmlns",
			},
			Value: "https://example.com",
		},
	}).NestedElement()

	n1.Key("prefixed", nil).String("abc")

	// call all close func's
	n1.Close()
	n.Close()

	e := []byte(`<payload><namespace xmlns:prefix="https://example.com"><prefixed>abc</prefixed></namespace></payload>`)
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
	encoder := xml.NewEncoder()
	o := encoder.RootElement("payload", nil)

	// nested `nested` shape
	n1 := o.NestedElement()
	o2 := n1.Key("nested", nil)

	// nested `value` shape
	n2 := o2.NestedElement()
	n2.Key("value", nil).String("expected value")

	// call all close fn's
	n2.Close()
	n1.Close()

	e := []byte(`<payload><nested><value>expected value</value></nested></payload>`)
	verify(t, encoder, e)
}

func TestEncodeMapString(t *testing.T) {
	encoder := xml.NewEncoder()
	o := encoder.RootElement("payload", nil)

	// nested `mapStr` shape
	n1 := o.NestedElement()
	m := n1.Key("mapstr", nil).Map()

	e := m.Entry()
	e.Key("key", nil).String("abc")
	e.Key("value", nil).Integer(123)
	e.Close()

	m.Close()
	n1.Close()

	ex := []byte(`<payload><mapstr><entry><key>abc</key><value>123</value></entry></mapstr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapFlatten(t *testing.T) {
	encoder := xml.NewEncoder()
	o := encoder.RootElement("payload", nil)

	// nested `mapStr` shape
	n1 := o.NestedElement()
	m := n1.Key("mapstr", nil).FlattenedMap()

	e := m.Entry()
	e.Key("key", nil).String("abc")
	e.Key("value", nil).Integer(123)
	e.Close()

	m.Close()
	n1.Close()

	ex := []byte(`<payload><mapstr><key>abc</key><value>123</value></mapstr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapNamed(t *testing.T) {
	encoder := xml.NewEncoder()
	o := encoder.RootElement("payload", nil)

	// nested `mapStr` shape
	n1 := o.NestedElement()
	m := n1.Key("mapNamed", nil).Map()

	// entry
	e := m.Entry()
	e.Key("namedKey", nil).String("abc")
	e.Key("namedValue", nil).Integer(123)
	e.Close()

	m.Close()
	n1.Close()

	ex := []byte(`<payload><mapNamed><entry><namedKey>abc</namedKey><namedValue>123</namedValue></entry></mapNamed></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapShape(t *testing.T) {
	encoder := xml.NewEncoder()
	o := encoder.RootElement("payload", nil)

	// nested `mapStr` shape
	n1 := o.NestedElement()
	m := n1.Key("mapShape", nil).Map()

	e := m.Entry()
	e.Key("key", nil).String("abc")

	n2 := e.Key("value", nil).NestedElement()
	n2.Key("shapeVal", nil).Integer(1)
	n2.Close()

	e.Close()

	// close map
	m.Close()
	n1.Close()

	ex := []byte(`<payload><mapShape><entry><key>abc</key><value><shapeVal>1</shapeVal></value></entry></mapShape></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapFlattenShape(t *testing.T) {
	encoder := xml.NewEncoder()
	root := encoder.RootElement("payload", nil)

	// nested `mapStr` shape
	n1 := root.NestedElement()
	m := n1.Key("mapShape", nil).FlattenedMap()

	e := m.Entry()
	e.Key("key", nil).String("abc")
	n2 := e.Key("value", nil).NestedElement()
	n2.Key("shapeVal", nil).Integer(1)
	n2.Close()
	e.Close()

	// close
	m.Close()
	n1.Close()

	ex := []byte(`<payload><mapShape><key>abc</key><value><shapeVal>1</shapeVal></value></mapShape></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapNamedShape(t *testing.T) {
	encoder := xml.NewEncoder()
	root := encoder.RootElement("payload", nil)

	// nested `mapStr` shape
	n1 := root.NestedElement()
	m := n1.Key("mapNamedShape", nil).Map()

	e := m.Entry()
	e.Key("namedKey", nil).String("abc")
	n2 := e.Key("namedValue", nil).NestedElement()
	n2.Key("shapeVal", nil).Integer(1)
	n2.Close()
	e.Close()

	// close
	m.Close()
	n1.Close()

	ex := []byte(`<payload><mapNamedShape><entry><namedKey>abc</namedKey><namedValue><shapeVal>1</shapeVal></namedValue></entry></mapNamedShape></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListString(t *testing.T) {
	encoder := xml.NewEncoder()
	r := encoder.RootElement("payload", nil)

	// Object key `liststr`
	n1 := r.NestedElement()

	a := n1.Key("liststr", nil).Array()
	a.Member().String("abc")
	a.Member().Integer(123)
	a.Close()

	// close all open objects
	n1.Close()

	ex := []byte(`<payload><liststr><member>abc</member><member>123</member></liststr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListFlatten(t *testing.T) {
	encoder := xml.NewEncoder()
	r := encoder.RootElement("payload", nil)

	// Object key `liststr`
	n1 := r.NestedElement()
	a := n1.Key("liststr", nil).FlattenedArray()
	a.Member().String("abc")
	a.Member().Integer(123)

	// close all open objects
	a.Close()
	n1.Close()

	ex := []byte(`<payload><liststr>abc</liststr><liststr>123</liststr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListNamed(t *testing.T) {
	encoder := xml.NewEncoder()
	r := encoder.RootElement("payload", nil)

	// Object key `liststr`
	n1 := r.NestedElement()
	a := n1.Key("liststr", nil).ArrayWithCustomName("namedMember")
	a.Member().String("abc")
	a.Member().Integer(123)
	a.Close()

	// close all open objects
	n1.Close()

	ex := []byte(`<payload><liststr><namedMember>abc</namedMember><namedMember>123</namedMember></liststr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListShape(t *testing.T) {
	encoder := xml.NewEncoder()
	r := encoder.RootElement("payload", nil)

	// Object key `liststr`
	n1 := r.NestedElement()
	a := n1.Key("liststr", nil).Array()

	// build entry
	e := a.Member()
	n2 := e.NestedElement()
	n2.Key("value", nil).String("abc")
	n2.Close()

	// build next entry
	e = a.Member()
	n3 := e.NestedElement()
	n3.Key("value", nil).Integer(123)
	n3.Close()

	// close all open objects
	a.Close()
	n1.Close()

	ex := []byte(`<payload><liststr><member><value>abc</value></member><member><value>123</value></member></liststr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListFlattenShape(t *testing.T) {
	encoder := xml.NewEncoder()
	r := encoder.RootElement("payload", nil)

	// Object key `liststr`
	n1 := r.NestedElement()
	a := n1.Key("liststr", nil).FlattenedArray()

	// build entry
	e := a.Member()
	n2 := e.NestedElement()
	n2.Key("value", nil).String("abc")
	n2.Close()

	// build next entry
	e = a.Member()
	n3 := e.NestedElement()
	n3.Key("value", nil).Integer(123)
	n3.Close()

	// close all open objects
	a.Close()
	n1.Close()

	ex := []byte(`<payload><liststr><value>abc</value></liststr><liststr><value>123</value></liststr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListNamedShape(t *testing.T) {
	encoder := xml.NewEncoder()
	r := encoder.RootElement("payload", nil)

	// Object key `liststr`
	n1 := r.NestedElement()
	a := n1.Key("liststr", nil).ArrayWithCustomName("namedMember")

	// build entry
	e := a.Member()
	n2 := e.NestedElement()
	n2.Key("value", nil).String("abc")
	n2.Close()

	// build next entry
	e = a.Member()
	n3 := e.NestedElement()
	n3.Key("value", nil).Integer(123)
	n3.Close()

	// close all open objects
	a.Close()
	n1.Close()

	ex := []byte(`<payload><liststr><namedMember><value>abc</value></namedMember><namedMember><value>123</value></namedMember></liststr></payload>`)
	verify(t, encoder, ex)
}
