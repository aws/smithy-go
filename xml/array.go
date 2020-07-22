package xml

// arrayMemberWrapper is the default member wrapper tag name for XML Array type
var arrayMemberWrapper = StartElement{
	Name: Name{Local: "member"},
}

// Array represents the encoding of a XML array type
type Array struct {
	w       writer
	scratch *[]byte

	// member start element is the array member wrapper start element
	memberStartElement *StartElement

	// array start element is the start element for the array
	// This is used by wrapped array serializers
	arrayStartElement *StartElement
}

// newArray returns an array encoder. It takes in a member wrapper name
// that is used to wrap array members.
//
// for eg. an array ["value1", "value2"] is represented as
// <List><member>value1</member><member>value2</member></List>
func newArray(w writer, scratch *[]byte, memberStartElement *StartElement, arrayStartElement *StartElement) *Array {
	writeStartElement(w, arrayStartElement)
	return &Array{
		w:                  w,
		scratch:            scratch,
		memberStartElement: memberStartElement,
		arrayStartElement:  arrayStartElement,
	}
}

// newFlattenedArray returns an Array Encoder. It takes member start and end element as argument
// The argument elements are used as a wrapper for each member entry of flattened array.
//
// for eg. an array `someList: ["value1", "value2"]` is represented as
// <someList>value1</someList><someList>value2</someList>.
func newFlattenedArray(w writer, scratch *[]byte, memberStartElement *StartElement) *Array {
	return &Array{w: w, scratch: scratch, memberStartElement: memberStartElement}
}

// Member adds a new member to the XML array.
// It returns a Value encoder.
func (a *Array) Member() Value {
	return newWrappedValue(a.w, a.scratch, a.memberStartElement)
}

// Close closes the array. For flattened array, this function is a noOp.
func (a *Array) Close() {
	// Flattened Map close is noOp.
	// arrayStartElement will be nil in case of flattened map
	if a.arrayStartElement == nil {
		return
	}

	end := a.arrayStartElement.End()
	writeEndElement(a.w, &end)
}
