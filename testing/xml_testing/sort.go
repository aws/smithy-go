package xml_testing

import (
	"bytes"
	"encoding/xml"
	"fmt"
	"io"
	"strings"

	"github.com/google/go-cmp/cmp"
)

type xmlAttrSlice []xml.Attr

func (x xmlAttrSlice) Len() int {
	return len(x)
}

func (x xmlAttrSlice) Less(i, j int) bool {
	spaceI, spaceJ := x[i].Name.Space, x[j].Name.Space
	localI, localJ := x[i].Name.Local, x[j].Name.Local
	valueI, valueJ := x[i].Value, x[j].Value

	spaceCmp := strings.Compare(spaceI, spaceJ)
	localCmp := strings.Compare(localI, localJ)
	valueCmp := strings.Compare(valueI, valueJ)

	if spaceCmp == -1 || (spaceCmp == 0 && (localCmp == -1 || (localCmp == 0 && valueCmp == -1))) {
		return true
	}

	return false
}

func (x xmlAttrSlice) Swap(i, j int) {
	x[i], x[j] = x[j], x[i]
}

// SortXML sorts the reader's XML elements
func SortXML(r io.Reader) (string, error) {
	var buf bytes.Buffer
	d := xml.NewDecoder(r)
	root, _ := XMLToStruct(d, nil)
	e := xml.NewEncoder(&buf)
	err := StructToXML(e, root, true)
	return buf.String(), err
}

// AssertXML asserts two xml body's by sorting the XML and comparing the strings
// It returns a boolean value for assertion and an error which may be returned in
// case of malformed xml found while sorting.
// In case of mismatched XML, the error string will contain the diff between the two XMLs.
func AssertXML(actual io.Reader, expected io.Reader) (bool, error) {
	actualString, err := SortXML(actual)
	if err != nil {
		return false, err
	}

	expectedString, err := SortXML(expected)
	if err != nil {
		return false, err
	}

	if diff := cmp.Diff(actualString, expectedString); len(diff) != 0 {
		return false, fmt.Errorf("found diff while comparing the xml: %s", diff)
	}

	return true, nil
}
