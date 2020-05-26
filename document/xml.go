package document

import (
	"encoding/xml"
	"io"
)

// RawXML provides a document marshaler for a byte slice containing a
// XML document.
type RawXML []byte

// MarshalXMLDocument attempts to marshal the Go value into a XML document.
// Returns a marshaled raw XML document value, or error if marshaling failed.
func MarshalXMLDocument(v interface{}) (RawXML, error) {
	b, err := xml.Marshal(v)
	if err != nil {
		return nil, err
	}
	return RawXML(b), nil
}

// UnmarshalDocument attempts to unmarshal the XML document into the Go type
// provided. The Go type must be a pointer value type, or the method will
// panic. Returns an error if the document could not be unmarshalled.
func (d RawXML) UnmarshalDocument(t interface{}) error {
	return xml.Unmarshal(d, t)
}

// XMLReader provides a DocumentMarshaler for a XML document from an
// io.Reader value.
type XMLReader struct {
	r io.Reader
}

// NewXMLReader returns a XML document unmarshaller for an io.Reader
// value.
func NewXMLReader(r io.Reader) *XMLReader {
	return &XMLReader{r: r}
}

func (d *XMLReader) Read(p []byte) (int, error) {
	return d.r.Read(p)
}

// Close will close the underlying reader if it is an io.Closer, otherwise this
// method does nothing.
func (d *XMLReader) Close() error {
	if v, ok := d.r.(io.Closer); ok {
		return v.Close()
	}
	return nil
}

// UnmarshalDocument attempts to unmarshal the XML document into the Go type
// provided. The Go type must be a pointer value type, or the method will
// panic. Returns an error if the document could not be unmarshalled.
//
// Calling this method multiple types most likely will result in EOF errors.
func (d *XMLReader) UnmarshalDocument(t interface{}) error {
	// TODO should the document unmarshal call close?
	defer d.Close()

	return xml.NewDecoder(d.r).Decode(t)
}
