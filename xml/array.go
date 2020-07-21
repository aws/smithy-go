package xml

import (
	"bytes"
	"encoding/xml"
)

// arrayKey is the default member wrapper tag name for XML Array type
const arrayKey = "member"

// Array represents the encoding of a XML array type
type Array struct {
	w       *bytes.Buffer
	scratch *[]byte

	memberName         string
	memberStartElement *xml.StartElement
	memberEndElement   *xml.EndElement

	arrayEndElement *xml.EndElement
}

// newArray returns an array encoder which sets the default array
// member wrapper to `member`.
//
// for eg. an array ["value1", "value2"] is represented as
// <List><member>value1</member><member>value2</member></List>
func newArray(w *bytes.Buffer, scratch *[]byte, arrayEndElement *xml.EndElement) *Array {
	return &Array{w: w, scratch: scratch, arrayEndElement: arrayEndElement, memberName: arrayKey}
}

// newArray returns an Array Encoder. It takes a name used for wrapping array members.
//
// for eg. an array ["value1", "value2"] with name as `customName` is represented as
// <List><customName>value1</customName><customName>value2</customName></List>
func newArrayWithCustomName(w *bytes.Buffer, scratch *[]byte, arrayEndElement *xml.EndElement, name string) *Array {
	return &Array{w: w, scratch: scratch, arrayEndElement: arrayEndElement, memberName: name}
}

// newFlattenedArray returns an Array Encoder. It takes openTagFn and closeTagFn as arguments
// The argument functions are used as a wrapper for each member entry of flattened array.
//
// for eg. an array `someList: ["value1", "value2"]` is represented as
// <someList>value1</someList><someList>value2</someList>.
func newFlattenedArray(w *bytes.Buffer, scratch *[]byte, memberStartElement *xml.StartElement, memberEndElement *xml.EndElement) *Array {
	return &Array{w: w, scratch: scratch, memberStartElement: memberStartElement, memberEndElement: memberEndElement}
}

// NewMember adds a new member to the XML array.
// It returns a Value encoder with array's element tag handler functions
func (a *Array) Member() Value {
	start := a.memberStartElement
	end := a.memberEndElement

	if start == nil {
		start = &xml.StartElement{
			Name: xml.Name{Local: a.memberName},
		}

		end = &xml.EndElement{
			Name: xml.Name{Local: a.memberName},
		}
	}

	return newValue(a.w, a.scratch, start, end)
}

func (a *Array) Close() {
	writeEndElement(a.w, a.arrayEndElement)
}
