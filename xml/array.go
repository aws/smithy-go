package xml

import (
	"bytes"
)

const arrayKey = "member"

type Array struct {
	w       *bytes.Buffer
	scratch *[]byte
	key     string
}

func newArray(w *bytes.Buffer, scratch *[]byte) *Array {
	return &Array{w: w, scratch: scratch, key: arrayKey}
}

func newArrayWithKey(w *bytes.Buffer, scratch *[]byte, key string) *Array {
	return &Array{w: w, scratch: scratch, key: key}
}

func (a *Array) Value() Value {
	writeKeyTag(a.w, a.key)
	a.w.WriteRune('>')

	return newValueWithKey(a.w, a.scratch, a.key)
}
