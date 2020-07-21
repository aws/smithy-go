package xml

import (
	"bytes"
)

// mapKey is the default member wrapper tag name for XML Map type
const mapKey = "entry"

// Map represents the encoding of a XML map type
type Map struct {
	w       *bytes.Buffer
	scratch *[]byte

	memberName         string
	memberStartElement *StartElement
	memberEndElement   *EndElement

	mapEndElement *EndElement
}

// newMap returns a map encoder which sets the default map
// entry wrapper to `entry`.
//
// for eg. someMap : {{key:"abc", value:"123"}} is represented as
// <someMap><entry><key>abc<key><value>123</value></entry><member>value2</member></someMap>
func newMap(w *bytes.Buffer, scratch *[]byte, endElement *EndElement) *Map {
	return &Map{w: w, scratch: scratch, mapEndElement: endElement, memberName: mapKey}
}

// newFlattenedMap returns a map Encoder. It takes openTagFn and closeTagFn as arguments
// The argument functions are used as a wrapper for each entry of flattened map.
//
// for eg. an array `someMap : {{key:"abc", value:"123"}}` is represented as
// `<someMap><key>abc</key><value>123</value></someMap>`.
func newFlattenedMap(w *bytes.Buffer, scratch *[]byte, memberStartElement *StartElement, memberEndElement *EndElement) *Map {
	return &Map{w: w, scratch: scratch, memberStartElement: memberStartElement, memberEndElement: memberEndElement}
}

// Entry returns a Value encoder with map's element and a closeFn.
// It writes the flattened parent wrapper start tag for each entry.
func (m *Map) Entry() (o *Object) {
	start := m.memberStartElement
	end := m.memberEndElement

	if start == nil {
		start = &StartElement{
			Name: Name{Local: m.memberName},
		}

		end = &EndElement{
			Name: Name{Local: m.memberName},
		}
	}

	_ = writeStartElement(m.w, start)

	return newObject(m.w, m.scratch, end)
}

func (m *Map) Close() {
	writeEndElement(m.w, m.mapEndElement)
}
