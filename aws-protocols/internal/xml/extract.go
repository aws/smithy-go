package xml

import (
	"bytes"
	"encoding/xml"
	"strings"
)

// ExtractElement finds the first occurrence of the named element in the XML
// and returns its content as raw bytes. If preserveEnclosing is true, the
// matched element's opening and closing tags (with attributes) are included
// in the result; otherwise only the inner content is returned.
//
// This is used to extract both success and error responses from the protocol
// XML that wraps them.
//
// For example for awsquery, with an operation output shape named
// XmlListsResult:
//
//	<XmlListsResponse>
//	 	<XmlListsResult>
//	 		<member1>foo</member1>
//	 		...
//	 	</XmlListsResult>
//	</XmlListsResponse>
//
// ExtractElement(payload, "XmlListsResult", false) yields:
//
//	<member1>foo</member1>...
//
// ExtractElement(payload, "XmlListsResult", true) yields:
//
//	<XmlListsResult><member1>foo</member1>...</XmlListsResult>
//
// ExtractElement will forward the io.EOF returned by the underlying decoder
// if the target element is not found, which the caller can index on if
// they're looking for an optional element.
func ExtractElement(payload []byte, name string, preserveEnclosing bool) ([]byte, error) {
	dec := xml.NewDecoder(bytes.NewReader(payload))
	for {
		tok, err := dec.Token()
		if err != nil {
			return nil, err
		}

		start, ok := tok.(xml.StartElement)
		if !ok || !strings.EqualFold(start.Name.Local, name) {
			continue
		}

		if !preserveEnclosing {
			return extract(dec)
		}

		var buf bytes.Buffer
		enc := xml.NewEncoder(&buf)
		if err := enc.EncodeToken(start); err != nil {
			return nil, err
		}
		if err := enc.Flush(); err != nil {
			return nil, err
		}

		depth := 1
		for depth > 0 {
			tok, err := dec.Token()
			if err != nil {
				return nil, err
			}
			if err := enc.EncodeToken(tok); err != nil {
				return nil, err
			}
			switch tok.(type) {
			case xml.StartElement:
				depth++
			case xml.EndElement:
				depth--
			}
		}

		if err := enc.Flush(); err != nil {
			return nil, err
		}
		return buf.Bytes(), nil
	}
}

func extract(dec *xml.Decoder) ([]byte, error) {
	var buf bytes.Buffer
	enc := xml.NewEncoder(&buf)
	depth := 1

	for depth > 0 {
		tok, err := dec.Token()
		if err != nil {
			return nil, err
		}

		switch t := tok.(type) {
		case xml.StartElement:
			depth++
			enc.EncodeToken(t)
		case xml.EndElement:
			depth--
			if depth > 0 {
				enc.EncodeToken(t)
			}
		case xml.CharData:
			enc.EncodeToken(t)
		}
	}

	enc.Flush()
	return buf.Bytes(), nil
}
