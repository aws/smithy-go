package xml

// arrayMemberWrapper is the default member wrapper tag name for XML Array type
const arrayMemberWrapper = "member"

// Array represents the encoding of a XML array type
type Array struct {
	w       writer
	scratch *[]byte

	memberWrapperName  string
	memberStartElement *StartElement
	memberEndElement   *EndElement

	arrayEndElement *EndElement
}

// newArray returns an array encoder. It takes in a member wrapper name
// that is used to wrap array members.
//
// for eg. an array ["value1", "value2"] is represented as
// <List><member>value1</member><member>value2</member></List>
func newArray(w writer, scratch *[]byte, arrayEndElement *EndElement, memberWrapperName string) *Array {
	return &Array{w: w, scratch: scratch, arrayEndElement: arrayEndElement, memberWrapperName: memberWrapperName}
}

// newFlattenedArray returns an Array Encoder. It takes member start and end element as argument
// The argument elements are used as a wrapper for each member entry of flattened array.
//
// for eg. an array `someList: ["value1", "value2"]` is represented as
// <someList>value1</someList><someList>value2</someList>.
func newFlattenedArray(w writer, scratch *[]byte, memberStartElement *StartElement, memberEndElement *EndElement) *Array {
	return &Array{w: w, scratch: scratch, memberStartElement: memberStartElement, memberEndElement: memberEndElement}
}

// Member adds a new member to the XML array.
// It returns a Value encoder.
func (a *Array) Member() Value {
	start := a.memberStartElement
	end := a.memberEndElement

	if start == nil {
		start = &StartElement{
			Name: Name{Local: a.memberWrapperName},
		}

		end = &EndElement{
			Name: Name{Local: a.memberWrapperName},
		}
	}

	return newValue(a.w, a.scratch, start, end)
}

// Close closes the array. For flattened array, this function is a noOp.
func (a *Array) Close() {
	writeEndElement(a.w, a.arrayEndElement)
	a.arrayEndElement = nil
}
