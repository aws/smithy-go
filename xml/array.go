package xml

import (
	"bytes"
)

// arrayKey is the default member wrapper tag name for XML Array type
const arrayKey = "member"

// Array represents the encoding of a XML array type
type Array struct {
	w          *bytes.Buffer
	scratch    *[]byte
	openTagFn  func()
	closeTagFn func()
}

// newArray returns an array encoder which sets the default array
// member wrapper to `member`.
//
// for eg. an array ["value1", "value2"] is represented as
// <List><member>value1</member><member>value2</member></List>
func newArray(w *bytes.Buffer, scratch *[]byte) *Array {
	var openTagFn = func() {
		writeOpenTag(w, arrayKey)
		w.WriteRune(rightAngleBracket)
	}

	var closeTagFn = func() {
		writeCloseTag(w, arrayKey)
	}

	return &Array{w: w, scratch: scratch, openTagFn: openTagFn, closeTagFn: closeTagFn}
}

// newArray returns an Array Encoder. It takes a name used for wrapping array members.
//
// for eg. an array ["value1", "value2"] with name as `customName` is represented as
// <List><customName>value1</customName><customName>value2</customName></List>
func newArrayWithCustomName(w *bytes.Buffer, scratch *[]byte, name string) *Array {
	var openTagFn = func() {
		writeOpenTag(w, name)
		w.WriteRune(rightAngleBracket)
	}

	var closeTagFn = func() {
		writeCloseTag(w, name)
	}

	return &Array{w: w, scratch: scratch, openTagFn: openTagFn, closeTagFn: closeTagFn}
}

// newFlattenedArray returns an Array Encoder. It takes openTagFn and closeTagFn as arguments
// The argument functions are used as a wrapper for each member entry of flattened array.
//
// for eg. an array `someList: ["value1", "value2"]` is represented as
// <someList>value1</someList><someList>value2</someList>.
func newFlattenedArray(w *bytes.Buffer, scratch *[]byte, openTagFn func(), closeTagFn func()) *Array {
	return &Array{w: w, scratch: scratch, openTagFn: openTagFn, closeTagFn: closeTagFn}
}

// NewMember adds a new member to the XML array.
// It returns a Value encoder with array's element tag handler functions
func (a *Array) NewMember() Value {
	return newValue(a.w, a.scratch, a.openTagFn, a.closeTagFn)
}
