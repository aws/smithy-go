package testing

import (
	"encoding/json"
	"fmt"
	"net/url"

	"github.com/google/go-cmp/cmp"
)

// JSONEqual compares two JSON documents and identifies if the documents contain
// the same values. Returns an error if the two documents are not equal.
func JSONEqual(expectBytes, actualBytes []byte) error {
	var expect interface{}
	if err := json.Unmarshal(expectBytes, &expect); err != nil {
		return fmt.Errorf("failed to unmarshal expected bytes, %v", err)
	}

	var actual interface{}
	if err := json.Unmarshal(actualBytes, &actual); err != nil {
		return fmt.Errorf("failed to unmarshal actual bytes, %v", err)
	}

	if diff := cmp.Diff(expect, actual); len(diff) != 0 {
		return fmt.Errorf("JSON mismatch (-expect +actual):\n%s", diff)
	}

	return nil
}

// AssertJSONEqual compares two JSON documents and identifies if the documents
// contain the same values. Emits a testing error, and returns false if the
// documents are not equal.
func AssertJSONEqual(t T, expect, actual []byte) bool {
	t.Helper()

	if err := JSONEqual(expect, actual); err != nil {
		t.Errorf("expect JSON documents to be equal, %v", err)
		return false
	}

	return true
}

// XMLEqual compares two XML documents and identifies if the documents contain
// the same values. Returns an error if the two documents are not equal.
func XMLEqual(expectBytes, actualBytes []byte) error {
	return fmt.Errorf("XMLEqual not implemented")
}

// AssertXMLEqual compares two XML documents and identifies if the documents
// contain the same values. Emits a testing error, and returns false if the
// documents are not equal.
func AssertXMLEqual(t T, expect, actual []byte) bool {
	t.Helper()

	if err := XMLEqual(expect, actual); err != nil {
		t.Errorf("expect XML documents to be equal, %v", err)
		return false
	}

	return true
}

// URLFormEqual compares two URLForm documents and identifies if the documents
// contain the same values. Returns an error if the two documents are not
// equal.
func URLFormEqual(expectBytes, actualBytes []byte) error {
	expected, err := url.ParseQuery(string(expectBytes))
	if err != nil {
		return fmt.Errorf("failed to unmarshal expected bytes, %v", err)
	}

	actual, err := url.ParseQuery(string(actualBytes))
	if err != nil {
		return fmt.Errorf("failed to unmarshal actual bytes, %v", err)
	}

	if diff := cmp.Diff(expected, actual); len(diff) != 0 {
		return fmt.Errorf("Query mismatch (-expect +actual):\n%s", diff)
	}

	return nil
}

// AssertURLFormEqual compares two URLForm documents and identifies if the
// documents contain the same values. Emits a testing error, and returns false
// if the documents are not equal.
func AssertURLFormEqual(t T, expect, actual []byte) bool {
	t.Helper()

	if err := URLFormEqual(expect, actual); err != nil {
		t.Errorf("expect URLForm documents to be equal, %v", err)
		return false
	}

	return true
}
