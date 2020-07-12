package xml

import (
	"bytes"
)

const mapKey = "entry"

type Map struct {
	w         *bytes.Buffer
	scratch   *[]byte
	parentKey string
}

func newMap(w *bytes.Buffer, scratch *[]byte) *Map {
	return &Map{w, scratch, mapKey}
}

func newFlattenedMap(w *bytes.Buffer, scratch *[]byte, parentKey string) *Map {
	return &Map{
		w:         w,
		scratch:   scratch,
		parentKey: parentKey,
	}
}

func (m *Map) Entry() *MapEntry {
	writeKeyTag(m.w, m.parentKey)
	m.w.WriteRune(rightAngleBracket)

	return newMapEntry(m.w, m.scratch, m.parentKey)
}

type MapEntry struct {
	w         *bytes.Buffer
	scratch   *[]byte
	parentKey string
	key       string
}

func newMapEntry(w *bytes.Buffer, scratch *[]byte, parentKey string) *MapEntry {
	return &MapEntry{
		w:         w,
		scratch:   scratch,
		parentKey: parentKey,
	}
}

// Key adds the given named key to the XML object.
// Returns a Value encoder that should be used to encode
// a XML value type.
func (m *MapEntry) Key(name string) Value {
	m.key = name
	writeKeyTag(m.w, m.key)
	defer m.w.WriteRune(rightAngleBracket)

	return newValueWithKey(m.w, m.scratch, name)
}

func (m *MapEntry) Close() {
	closeKeyTag(m.w, &m.parentKey)
}

/*=====================================================================*/

// // Flattened Map
// type FlattenedMap struct {
// 	w         *bytes.Buffer
// 	scratch   *[]byte
// 	parentKey string
// }

//
// func (f *FlattenedMap) Entry() *FlattenedMapEntry {
// 	// write parent key tag
// 	writeKeyTag(f.w, f.parentKey)
// 	f.w.WriteRune(rightAngleBracket)
//
// 	return newFlattenedMapEntry(f.w, f.scratch, f.parentKey)
// }
//
// type FlattenedMapEntry struct {
// 	w         *bytes.Buffer
// 	scratch   *[]byte
// 	parentKey string
// 	key       string
// }
//
// func newFlattenedMapEntry(w *bytes.Buffer, scratch *[]byte, parentKey string) *FlattenedMapEntry {
// 	return &FlattenedMapEntry{
// 		w:         w,
// 		scratch:   scratch,
// 		parentKey: parentKey,
// 	}
// }
//
// func (e *FlattenedMapEntry) Key(name string) Value {
// 	e.key = name
//
// 	// write
// 	writeKeyTag(e.w, e.key)
// 	e.w.WriteRune(rightAngleBracket)
//
// 	return newValueWithKey(e.w, e.scratch, e.key)
// }
//
// func (e *FlattenedMapEntry) Close() {
// 	closeKeyTag(e.w, &e.parentKey)
// }
