package json

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

var skipTests = map[string]bool{
	// Trailing content after a valid top-level value, like stdlib, we parse
	// one complete value and stop.
	"n_array_comma_after_close.json":                       true,
	"n_array_extra_close.json":                             true,
	"n_multidigit_number_then_00.json":                     true,
	"n_object_trailing_comment.json":                       true,
	"n_object_trailing_comment_open.json":                  true,
	"n_object_trailing_comment_slash_open.json":            true,
	"n_object_trailing_comment_slash_open_incomplete.json": true,
	"n_object_with_trailing_garbage.json":                  true,
	"n_string_with_trailing_garbage.json":                  true,
	"n_structure_array_trailing_garbage.json":              true,
	"n_structure_array_with_extra_array_close.json":        true,
	"n_structure_close_unopened_array.json":                true,
	"n_structure_double_array.json":                        true,
	"n_structure_number_with_trailing_garbage.json":        true,
	"n_structure_object_followed_by_closing_object.json":   true,
	"n_structure_object_with_trailing_garbage.json":        true,
	"n_structure_trailing_#.json":                          true,

	// These numbers have valid JSON grammar but overflow float64, stdlib
	// rejects them at tokenize, we reject in the ShapeDeserializer.
	"i_number_huge_exp.json":            true,
	"i_number_neg_int_huge_exp.json":    true,
	"i_number_pos_double_huge_exp.json": true,
	"i_number_real_neg_overflow.json":   true,
	"i_number_real_pos_overflow.json":   true,
}

// Run test cases from https://github.com/nst/JSONTestSuite.
func TestJSONTestSuite(t *testing.T) {
	entries, err := os.ReadDir("testdata/test_parsing")
	if err != nil {
		t.Fatal(err)
	}

	for _, ent := range entries {
		if !strings.HasSuffix(ent.Name(), ".json") {
			continue
		}

		t.Run(ent.Name(), func(t *testing.T) {
			if skipTests[ent.Name()] {
				t.Skip()
			}

			data, err := os.ReadFile(filepath.Join("testdata/test_parsing", ent.Name()))
			if err != nil {
				t.Fatal(err)
			}

			ours := testParse(data)
			switch {
			case strings.HasPrefix(ent.Name(), "y_"): // should accept
				if ours != nil {
					t.Errorf("must accept, got error: %v", ours)
				}
			case strings.HasPrefix(ent.Name(), "n_"): // should reject
				if ours == nil {
					t.Error("must reject")
				}
			case strings.HasPrefix(ent.Name(), "i_"): // implementation-specific, ensure we match stdlib
				var v any
				theirs := json.NewDecoder(bytes.NewReader(data)).Decode(&v)
				if (ours == nil) != (theirs == nil) {
					t.Errorf("stdlib mismatch: us=%v stdlib=%v", errstr(ours), errstr(theirs))
				}
			}
		})
	}
}

func errstr(err error) string {
	if err == nil {
		return "accept"
	}
	return "reject: " + err.Error()
}
