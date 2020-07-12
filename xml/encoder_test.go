package xml_test

import (
	"bytes"
	"testing"

	"github.com/awslabs/smithy-go/xml"
)

func TestEncoder(t *testing.T) {
	encoder := xml.NewEncoder()
	v := encoder.Value

	obj := v.Object()
	obj.Key("stringKey").String("stringValue")
	obj.Key("integerKey").Integer(1024)
	obj.Key("floatKey").Float(3.14)

	vk := obj.Key("foo")
	vk.Object().Key("byteSlice").String("Zm9vIGJhcg==")
	vk.Close()

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
	v := encoder.Value

	obj := v.Object()
	obj.Key("payload", func() xml.TagMetadata {
		return xml.TagMetadata{
			AttributeName:  "attrkey",
			AttributeValue: "value",
		}
	}).Close()

	expect := `<payload attrkey="value"></payload>`

	if e, a := expect, encoder.String(); e != a {
		t.Errorf("expect bodies to match, did not.\n,\tExpect:\n%s\n\tActual:\n%s\n", e, a)
	}
}

func TestEncodeNamespace(t *testing.T) {
	encoder := xml.NewEncoder()
	v := encoder.Value
	obj := v.Object()

	// nested `payload` shape
	o := obj.Key("payload")

	// nested `namespace` shape
	n := o.Object().Key("namespace", func() xml.TagMetadata {
		return xml.TagMetadata{
			NamespacePrefix: "prefix",
			NamespaceURI:    "https://example.com",
		}
	})

	// nested `prefixed` shape
	n.Object().Key("prefixed").String("abc")

	// close all nested object tag
	n.Close()
	o.Close()

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
	obj := v.Object()

	// nested `payload` shape
	o := obj.Key("payload")

	// nested `nested` shape
	n := o.Object().Key("nested")

	// nested `prefixed` shape
	n.Object().Key("value").String("expected value")

	// close all nested object tag
	n.Close()
	o.Close()

	e := []byte(`<payload><nested><value>expected value</value></nested></payload>`)
	verify(t, encoder, e)
}

func TestEncodeMapString(t *testing.T) {
	encoder := xml.NewEncoder()
	obj := encoder.Object()

	o := obj.Key("payload")

	// create map object
	m := o.Object().Key("mapstr")

	e := m.Map().Entry()
	e.Key("key").String("abc")
	e.Key("value").String("123")
	e.Close()

	m.Close()
	o.Close()

	ex := []byte(`<payload><mapstr><entry><key>abc</key><value>123</value></entry></mapstr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapFlatten(t *testing.T) {
	encoder := xml.NewEncoder()
	obj := encoder.Object()

	o := obj.Key("payload")

	// Object key `mapFlatten`
	m := o.Object().SetKey("mapFlatten")

	// map entries
	e := m.FlattenedMap().Entry()
	e.Key("key").String("abc")
	e.Key("value").Integer(123)

	// close all open objects
	e.Close()
	o.Close()

	ex := []byte(`<payload><mapFlatten><key>abc</key><value>123</value></mapFlatten></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapNamed(t *testing.T) {
	encoder := xml.NewEncoder()
	obj := encoder.Object()

	o := obj.Key("payload")

	// Object key `mapNamed`
	m := o.Object().Key("mapNamed")

	// map entries
	e := m.Map().Entry()
	e.Key("namedKey").String("abc")
	e.Key("namedValue").Integer(123)

	// close all open objects
	e.Close()
	m.Close()
	o.Close()

	ex := []byte(`<payload><mapNamed><entry><namedKey>abc</namedKey><namedValue>123</namedValue></entry></mapNamed></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapShape(t *testing.T) {
	encoder := xml.NewEncoder()
	obj := encoder.Object()

	o := obj.Key("payload")

	// Object key `mapNamed`
	m := o.Object().Key("mapShape")

	// map entries
	e := m.Map().Entry()
	e.Key("key").String("abc")

	v := e.Key("value")
	v.Object().Key("value").Integer(1)
	v.Close()

	// close all open objects
	e.Close()
	m.Close()
	o.Close()

	ex := []byte(`<payload><mapShape><entry><key>abc</key><value><value>1</value></value></entry></mapShape></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapFlattenShape(t *testing.T) {
	encoder := xml.NewEncoder()
	obj := encoder.Object()

	o := obj.Key("payload")

	// Object key `mapNamed`
	m := o.Object().SetKey("mapFlattenShape")

	// map entries
	e := m.FlattenedMap().Entry()
	e.Key("key").String("abc")

	v := e.Key("value")
	v.Object().Key("value").Integer(1)
	v.Close()

	// close all open objects
	m.Close()
	o.Close()

	ex := []byte(`<payload><mapFlattenShape><key>abc</key><value><value>1</value></value></mapFlattenShape></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeMapNamedShape(t *testing.T) {
	encoder := xml.NewEncoder()
	obj := encoder.Object()

	o := obj.Key("payload")

	// Object key `mapNamed`
	m := o.Object().Key("mapNamedShape")

	// map entries
	e := m.Map().Entry()
	e.Key("namedKey").String("abc")

	v := e.Key("namedValue")
	v.Object().Key("value").Integer(1)
	v.Close()

	// close all open objects
	e.Close()
	m.Close()
	o.Close()

	ex := []byte(`<payload><mapNamedShape><entry><namedKey>abc</namedKey><namedValue><value>1</value></namedValue></entry></mapNamedShape></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListString(t *testing.T) {
	encoder := xml.NewEncoder()
	obj := encoder.Object()

	o := obj.Key("payload")

	// Object key `liststr`
	m := o.Object().Key("liststr")

	// map entries
	e := m.Array()
	e.Value().String("abc")
	e.Value().Integer(123)

	// close all open objects
	m.Close()
	o.Close()

	ex := []byte(`<payload><liststr><member>abc</member><member>123</member></liststr></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListFlatten(t *testing.T) {
	encoder := xml.NewEncoder()
	obj := encoder.Object()

	o := obj.Key("payload")

	// Object key `liststr`
	m := o.Object().SetKey("listFlatten")

	// map entries
	e := m.FlattenedArray()
	e.Value().String("abc")
	e.Value().Integer(123)

	// close all open objects
	// m.Close()
	o.Close()

	ex := []byte(`<payload><listFlatten>abc</listFlatten><listFlatten>123</listFlatten></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListNamed(t *testing.T) {
	encoder := xml.NewEncoder()
	obj := encoder.Object()

	o := obj.Key("payload")

	// Object key `liststr`
	m := o.Object().Key("listNamed")

	// map entries
	e := m.ArrayWithKey("namedMember")
	e.Value().String("abc")
	e.Value().Integer(123)

	// close all open objects
	m.Close()
	o.Close()

	ex := []byte(`<payload><listNamed><namedMember>abc</namedMember><namedMember>123</namedMember></listNamed></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListShape(t *testing.T) {
	encoder := xml.NewEncoder()
	obj := encoder.Object()

	o := obj.Key("payload")

	// Object key `liststr`
	m := o.Object().Key("listShape")
	a := m.Array()

	// array entries
	v := a.Value()
	ov := v.Object()
	ov.Key("value").String("abc")
	v.Close()

	// array entries
	v = a.Value()
	ov = v.Object()
	ov.Key("value").Integer(123)
	v.Close()

	// close all open objects
	m.Close()
	o.Close()

	ex := []byte(`<payload><listShape><member><value>abc</value></member><member><value>123</value></member></listShape></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListFlattenShape(t *testing.T) {
	encoder := xml.NewEncoder()
	obj := encoder.Object()
	o := obj.Key("payload")

	// Object key `listFlattenShape`
	m := o.Object().SetKey("listFlattenShape")
	a := m.FlattenedArray()

	// array entries
	v := a.Value()
	ov := v.Object()
	ov.Key("value").String("abc")
	v.Close()

	// array entries
	v = a.Value()
	ov = v.Object()
	ov.Key("value").Integer(123)
	v.Close()

	// close all open objects
	// m.Close()
	o.Close()

	ex := []byte(`<payload><listFlattenShape><value>abc</value></listFlattenShape><listFlattenShape><value>123</value></listFlattenShape></payload>`)
	verify(t, encoder, ex)
}

func TestEncodeListNamedShape(t *testing.T) {
	encoder := xml.NewEncoder()
	obj := encoder.Object()

	o := obj.Key("payload")

	// Object key `liststr`
	m := o.Object().Key("listNamedShape")
	a := m.ArrayWithKey("namedMember")

	// array entries
	v := a.Value()
	ov := v.Object()
	ov.Key("value").String("abc")
	v.Close()

	// array entries
	v = a.Value()
	ov = v.Object()
	ov.Key("value").Integer(123)
	v.Close()

	// close all open objects
	m.Close()
	o.Close()

	ex := []byte(`<payload><listNamedShape><namedMember><value>abc</value></namedMember><namedMember><value>123</value></namedMember></listNamedShape></payload>`)
	verify(t, encoder, ex)
}
