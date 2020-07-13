package xml_test

import (
	"bytes"
	"testing"

	"github.com/awslabs/smithy-go/xml"
)

func TestEncoder(t *testing.T) {
	encoder := xml.NewEncoder()

	obj := encoder.RootElement()
	obj.Key("stringKey").String("stringValue")
	obj.Key("integerKey").Integer(1024)
	obj.Key("floatKey").Float(3.14)

	vk, closeFn := obj.Key("foo").NestedElement()
	vk.Key("byteSlice").String("Zm9vIGJhcg==")

	if closeFn != nil {
		closeFn()
	}

	e := []byte(`<stringKey>stringValue</stringKey><integerKey>1024</integerKey><floatKey>3.14</floatKey><foo><byteSlice>Zm9vIGJhcg==</byteSlice></foo>`)
	if a := encoder.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}

	if a := encoder.String(); string(e) != a {
		t.Errorf("expected %s, but got %s", e, a)
	}
}

func TestEncodeAttribute(t *testing.T) {
	encoder := xml.NewEncoder()
	obj := encoder.RootElement()

	 obj.Key("payload", func(t *xml.TagMetadata) {
		t.AttributeName = "attrkey"
		t.AttributeValue = "value"
	}).Null()


	expect := `<payload attrkey="value"></payload>`

	if e, a := expect, encoder.String(); e != a {
		t.Errorf("expect bodies to match, did not.\n,\tExpect:\n%s\n\tActual:\n%s\n", e, a)
	}
}

func TestEncodeNamespace(t *testing.T) {
	encoder := xml.NewEncoder()
	obj := encoder.RootElement()

	// nested `payload` shape
	o := obj.Key("payload")
	n, closeFn1 := o.NestedElement()

	// nested `namespace` shape
	n1 := n.Key("namespace", func(t *xml.TagMetadata) {
		t.NamespacePrefix = "prefix"
		t.NamespaceURI = "https://example.com"
	})

	// nested `prefixed` shape
	n2, closeFn2 := n1.NestedElement()
	n2.Key("prefixed").String("abc")

	// call all close func's
	closeFn2()
	closeFn1()

	e := []byte(`<payload><namespace xmlns:prefix="https://example.com"><prefixed>abc</prefixed></namespace></payload>`)
	verify(t, encoder, e)
}

func verify(t *testing.T, encoder *xml.Encoder, e []byte) {
	if a := encoder.Bytes(); bytes.Compare(e, a) != 0 {
		t.Errorf("expected %+q, but got %+q", e, a)
	}

	if a := encoder.String(); string(e) != a {
		t.Errorf("expected %s, but got %s", e, a)
	}
}

func TestEncodeNestedShape(t *testing.T) {
	encoder := xml.NewEncoder()
	v := encoder.Value
	root := v.RootElement()

	// nested `payload` shape
	o := root.Key("payload")

	// nested `nested` shape
	n1, closeFn1 := o.NestedElement()
	o2 := n1.Key("nested")

	// nested `value` shape
	n2, closeFn2 := o2.NestedElement()
	n2.Key("value").String("expected value")

	// call all close fn's
	closeFn2()
	closeFn1()

	e := []byte(`<payload><nested><value>expected value</value></nested></payload>`)
	verify(t, encoder, e)
}

func TestEncodeMapString(t *testing.T) {
	encoder := xml.NewEncoder()
	v := encoder.Value
	root := v.RootElement()

	// nested `payload` shape
	o := root.Key("payload")

	// nested `mapStr` shape
	ns, closeFn0 := o.NestedElement()
	o1 := ns.Key("mapstr")

	m, closeFn1 := o1.Map()

	e, closeFn2 := m.Entry()
	e.Key("key").String("abc")
	e.Key("value").Integer(123)
	closeFn2()

	closeFn1()
	closeFn0()

	ex := []byte(`<payload><mapstr><entry><key>abc</key><value>123</value></entry></mapstr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapFlatten(t *testing.T) {
	encoder := xml.NewEncoder()
	v := encoder.Value
	root := v.RootElement()

	// nested `payload` shape
	o := root.Key("payload")

	// nested `mapStr` shape
	ns, closeFn0 := o.NestedElement()
	o1 := ns.Key("mapstr")

	m := o1.FlattenedMap()

	e, closeFn2 := m.Entry()
	e.Key("key").String("abc")
	e.Key("value").Integer(123)
	closeFn2()

	closeFn0()

	ex := []byte(`<payload><mapstr><key>abc</key><value>123</value></mapstr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapNamed(t *testing.T) {
	encoder := xml.NewEncoder()
	v := encoder.Value
	root := v.RootElement()

	// nested `payload` shape
	o := root.Key("payload")

	// nested `mapStr` shape
	ns, closeFn0 := o.NestedElement()
	o1 := ns.Key("mapNamed")

	m, closeFn1 := o1.Map()

	e, closeFn2 := m.Entry()
	e.Key("namedKey").String("abc")
	e.Key("namedValue").Integer(123)
	closeFn2()

	closeFn1()
	closeFn0()

	ex := []byte(`<payload><mapNamed><entry><namedKey>abc</namedKey><namedValue>123</namedValue></entry></mapNamed></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapShape(t *testing.T) {
	encoder := xml.NewEncoder()
	v := encoder.Value
	root := v.RootElement()

	// nested `payload` shape
	o := root.Key("payload")

	// nested `mapStr` shape
	ns, closeFn0 := o.NestedElement()
	o1 := ns.Key("mapShape")

	m, closeFn1 := o1.Map()

	e, closeFn2 := m.Entry()
	e.Key("key").String("abc")

	//  delegate to shape encoding function
	o2, closeFn3 := e.Key("value").NestedElement()
	o2.Key("shapeVal").Integer(1)

	// close all
	closeFn3()
	closeFn2()
	closeFn1()
	closeFn0()

	ex := []byte(`<payload><mapShape><entry><key>abc</key><value><shapeVal>1</shapeVal></value></entry></mapShape></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapFlattenShape(t *testing.T) {
	encoder := xml.NewEncoder()
	v := encoder.Value
	root := v.RootElement()

	// nested `payload` shape
	o := root.Key("payload")

	// nested `mapStr` shape
	ns, closeFn0 := o.NestedElement()
	o1 := ns.Key("mapShape")

	m := o1.FlattenedMap()

	e, closeFn2 := m.Entry()
	e.Key("key").String("abc")

	//  delegate to shape encoding function
	o2, closeFn3 := e.Key("value").NestedElement()
	o2.Key("shapeVal").Integer(1)

	// close all
	closeFn3()
	closeFn2()
	closeFn0()

	ex := []byte(`<payload><mapShape><key>abc</key><value><shapeVal>1</shapeVal></value></mapShape></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapNamedShape(t *testing.T) {
	encoder := xml.NewEncoder()
	v := encoder.Value
	root := v.RootElement()

	// nested `payload` shape
	o := root.Key("payload")

	// nested `mapStr` shape
	ns, closeFn0 := o.NestedElement()
	o1 := ns.Key("mapNamedShape")

	m, closeFn1 := o1.Map()

	e, closeFn2 := m.Entry()
	e.Key("namedKey").String("abc")

	//  delegate to shape encoding function
	o2, closeFn3 := e.Key("namedValue").NestedElement()
	o2.Key("shapeVal").Integer(1)

	// close all
	closeFn3()
	closeFn2()
	closeFn1()
	closeFn0()

	ex := []byte(`<payload><mapNamedShape><entry><namedKey>abc</namedKey><namedValue><shapeVal>1</shapeVal></namedValue></entry></mapNamedShape></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListString(t *testing.T) {
	encoder := xml.NewEncoder()
	r := encoder.RootElement()

	o := r.Key("payload")

	// Object key `liststr`
	n1, closeFn1 := o.NestedElement()
	n2 := n1.Key("liststr")

	// build array
	a, closeFn2 := n2.Array()
	a.Add().String("abc")
	a.Add().Integer(123)

	// close all open objects
	closeFn2()
	closeFn1()

	ex := []byte(`<payload><liststr><member>abc</member><member>123</member></liststr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListFlatten(t *testing.T) {
	encoder := xml.NewEncoder()
	r := encoder.RootElement()

	o := r.Key("payload")

	// Object key `liststr`
	n1, closeFn1 := o.NestedElement()
	n2 := n1.Key("liststr")

	// build array
	a := n2.FlattenedArray()
	a.Add().String("abc")
	a.Add().Integer(123)

	// close all open objects
	closeFn1()

	ex := []byte(`<payload><liststr>abc</liststr><liststr>123</liststr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListNamed(t *testing.T) {
	encoder := xml.NewEncoder()
	r := encoder.RootElement()

	o := r.Key("payload")

	// Object key `liststr`
	n1, closeFn1 := o.NestedElement()
	n2 := n1.Key("liststr")

	// build array
	a, closeFn2 := n2.ArrayWithCustomName("namedMember")
	a.Add().String("abc")
	a.Add().Integer(123)

	// close all open objects
	closeFn2()
	closeFn1()

	ex := []byte(`<payload><liststr><namedMember>abc</namedMember><namedMember>123</namedMember></liststr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListShape(t *testing.T) {
	encoder := xml.NewEncoder()
	r := encoder.RootElement()

	o := r.Key("payload")

	// Object key `liststr`
	n1, closeFn1 := o.NestedElement()
	n2 := n1.Key("liststr")

	// build array
	a, closeFn2 := n2.Array()

	// Entries
	e := a.Add()

	// build entry
	n3, closeFn3 := e.NestedElement()
	n3.Key("value").String("abc")
	closeFn3()

	// build next entry
	n4, closeFn4 := e.NestedElement()
	n4.Key("value").Integer(123)
	closeFn4()

	// close all open objects
	closeFn2()
	closeFn1()

	ex := []byte(`<payload><liststr><member><value>abc</value></member><member><value>123</value></member></liststr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListFlattenShape(t *testing.T) {
	encoder := xml.NewEncoder()
	r := encoder.RootElement()

	o := r.Key("payload")

	// Object key `liststr`
	n1, closeFn1 := o.NestedElement()
	n2 := n1.Key("liststr")

	// build array
	a := n2.FlattenedArray()

	// Entries
	e := a.Add()

	// build entry
	n3, closeFn3 := e.NestedElement()
	n3.Key("value").String("abc")
	closeFn3()

	// build next entry
	n4, closeFn4 := e.NestedElement()
	n4.Key("value").Integer(123)
	closeFn4()

	// close all open objects
	closeFn1()

	ex := []byte(`<payload><liststr><value>abc</value></liststr><liststr><value>123</value></liststr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListNamedShape(t *testing.T) {
	encoder := xml.NewEncoder()
	r := encoder.RootElement()

	o := r.Key("payload")

	// Object key `liststr`
	n1, closeFn1 := o.NestedElement()
	n2 := n1.Key("liststr")

	// build array
	a, closeFn2 := n2.ArrayWithCustomName("namedMember")

	// Entries
	e := a.Add()

	// build entry
	n3, closeFn3 := e.NestedElement()
	n3.Key("value").String("abc")
	closeFn3()

	// build next entry
	n4, closeFn4 := e.NestedElement()
	n4.Key("value").Integer(123)
	closeFn4()

	// close all open objects
	closeFn2()
	closeFn1()

	ex := []byte(`<payload><liststr><namedMember><value>abc</value></namedMember><namedMember><value>123</value></namedMember></liststr></payload>`)
	verify(t, encoder, ex)
}
