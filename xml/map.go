package xml

import (
	"bytes"
)

// mapKey is the default member wrapper tag name for XML Map type
const mapKey = "entry"

// Map represents the encoding of a XML map type
type Map struct {
	w          *bytes.Buffer
	scratch    *[]byte
	openTagFn  func()
	closeTagFn func()
}

// newMap returns a map encoder which sets the default map
// entry wrapper to `entry`.
//
// for eg. someMap : {{key:"abc", value:"123"}} is represented as
// <someMap><entry><key>abc<key><value>123</value></entry><member>value2</member></someMap>
func newMap(w *bytes.Buffer, scratch *[]byte) *Map {
	var openTagFn = func() {
		writeOpenTag(w, mapKey)
		w.WriteRune(rightAngleBracket)
	}

	var closeTagFn = func() {
		writeCloseTag(w, mapKey)
	}

	return &Map{w: w, scratch: scratch, openTagFn: openTagFn, closeTagFn: closeTagFn}
}

// newFlattenedMap returns a map Encoder. It takes openTagFn and closeTagFn as arguments
// The argument functions are used as a wrapper for each entry of flattened map.
//
// for eg. an array `someMap : {{key:"abc", value:"123"}}` is represented as
// `<someMap><key>abc</key><value>123</value></someMap>`.
func newFlattenedMap(w *bytes.Buffer, scratch *[]byte, openTagFn func(), closeTagFn func()) *Map {
	return &Map{w: w, scratch: scratch, openTagFn: openTagFn, closeTagFn: closeTagFn}
}

// Entry returns a Value encoder with map's element and a closeFn.
// It writes the flattened parent wrapper start tag for each entry.
func (m *Map) Entry() (o *Object, closeFn func()) {
	m.openTagFn()
	return newObject(m.w, m.scratch), m.closeTagFn
}
