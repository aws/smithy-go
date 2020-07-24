package xml

// mapEntryWrapper is the default member wrapper start element for XML Map entry
var mapEntryWrapper = StartElement{
	Name: Name{Local: "entry"},
}

// Map represents the encoding of a XML map type
type Map struct {
	w       writer
	scratch *[]byte

	// member start element is the map entry wrapper start element
	memberStartElement StartElement

	// map start element is the start element for the map
	// This is used by wrapped map serializers
	mapStartElement StartElement

	isFlattened bool
}

// newMap returns a map encoder which sets the default map
// entry wrapper to `entry`.
//
// for eg. someMap : {{key:"abc", value:"123"}} is represented as
// <someMap><entry><key>abc<key><value>123</value></entry></someMap>
// The returned Map must be closed.
func newMap(w writer, scratch *[]byte, startElement StartElement) *Map {
	// write map start element
	writeStartElement(w, startElement)

	return &Map{
		w:                  w,
		scratch:            scratch,
		memberStartElement: mapEntryWrapper,
		mapStartElement:    startElement,
	}
}

// newFlattenedMap returns a map Encoder. It takes in member start and end element as arguments.
// The argument elements are used as a wrapper for each entry of flattened map.
//
// for eg. an array `someMap : {{key:"abc", value:"123"}}` is represented as
// `<someMap><key>abc</key><value>123</value></someMap>`.
func newFlattenedMap(w writer, scratch *[]byte, memberStartElement StartElement) *Map {
	return &Map{
		w:                  w,
		scratch:            scratch,
		memberStartElement: memberStartElement,
		isFlattened:        true,
	}
}

// Entry returns a Value encoder with map's element.
// It writes the member wrapper start tag for each entry.
func (m *Map) Entry() Value {
	v := newWrappedValue(m.w, m.scratch, m.memberStartElement)
	v.isFlattened = m.isFlattened
	return v
}

// Close closes a map.
func (m *Map) Close() {
	// Flattened map close is a noOp.
	// mapEndElement is nil for flattened map.
	if m.mapStartElement.isZero() {
		return
	}

	writeEndElement(m.w, m.mapStartElement.End())
}
