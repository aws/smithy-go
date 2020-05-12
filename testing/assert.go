package testing

import (
	"encoding/json"
	"fmt"

	"github.com/google/go-cmp/cmp"
)

// T provides the testing interface for capturing failures with testing assert
// utilities.
type T interface {
	Error(args ...interface{})
	Errorf(format string, args ...interface{})
	Helper()
}

// JSONEqual compares to JSON documents and identifies if the documents contain
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

// AssertJSONEqual compares to JSON documents and identifies if the documents
// contain the same values. Emits a testing error, and returns false if the
// documents are not equal.
func AssertJSONEqual(t T, expect, actual []byte) bool {
	t.Helper()

	if err := JSONEqual(expect, actual); err != nil {
		t.Errorf("expect JSON equal, %v", err)
		return false
	}

	return true
}
