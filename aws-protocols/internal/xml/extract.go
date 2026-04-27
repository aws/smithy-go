package xml

import (
	"bytes"
	"encoding/xml"
	"strings"
)

// ExtractElement finds the first occurrence of the named element in the XML
// and returns its INNER content as raw bytes.
//
// This is used to extract both success and error responses from the protocol
// XML that wraps them.
//
// e.g. for awsquery, with an operation output shape named XmlListsResult:
//
//	<XmlListsResponse>
//	 	<XmlListsResult>
//	 		<member1>foo</member1>
//	 		...
//	 	</XmlListsResult>
//	</XmlListsResponse>
//
// ExtractElement gives you "<member1>foo</member1>...".
//
// ExtractElement will forward the io.EOF returned by the underlying decoder if
// the target element is not found, which the caller can index on if they're
// looking for an optional element.
func ExtractElement(payload []byte, name string) ([]byte, error) {
	dec := xml.NewDecoder(bytes.NewReader(payload))
	for {
		tok, err := dec.Token()
		if err != nil {
			return nil, err
		}

		if start, ok := tok.(xml.StartElement); ok {
			if strings.EqualFold(start.Name.Local, name) {
				return extract(dec)
			}
		}
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
