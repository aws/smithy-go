package rulesfn

import (
	"strings"
	"testing"
	smithyerrep "github.com/aws/smithy-go/error/endpoints"
	"github.com/aws/smithy-go/ptr"
)

func TestSubStrin(t *testing.T) {
	cases := map[string]struct {
		input       string
		start, stop int
		reverse     bool
		expect      *string
		expectErr   string
	}{
		"prefix": {
			input: "abcde", start: 0, stop: 3, reverse: false,
			expect: ptr.String("abc"),
		},
		"prefix max-ascii": {
			input: "abcde\u007F", start: 0, stop: 3, reverse: false,
			expect: ptr.String("abc"),
		},
		"suffix reverse": {
			input: "abcde", start: 0, stop: 3, reverse: true,
			expect: ptr.String("cde"),
		},
		"too long": {
			input: "ab", start: 0, stop: 3, reverse: false,
			expectErr: "indexes overlap, or invalid index,",
		},
		"invalid start index": {
			input: "ab", start: -1, stop: 3, reverse: false,
			expectErr: "indexes overlap, or invalid index,",
		},
		"invalid stop index": {
			input: "ab", start: 0, stop: 0, reverse: false,
			expectErr: "indexes overlap, or invalid index,",
		},
		"non-ascii": {
			input: "ab🐱", start: 0, stop: 1, reverse: false,
			expectErr: "input contains non-ASCII characters,",
		},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			ec := smithyerrep.NewErrorCollector()
			actual := SubString(c.input, c.start, c.stop, c.reverse, ec)
			if c.expect == nil {
				if actual != nil {
					t.Fatalf("expect no result, got %v", *actual)
				}
				if e, a := c.expectErr, ec.Error(); !strings.Contains(a, e) {
					t.Errorf("expect %q error in %q", e, a)
				}
				return
			}

			if actual == nil {
				t.Fatalf("expect result, got none")
			}

			if e, a := *c.expect, *actual; e != a {
				t.Errorf("expect %q, got %q", e, a)
			}
		})
	}
}
