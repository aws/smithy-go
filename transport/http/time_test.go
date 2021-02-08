package http_test

import (
	"testing"
	"time"

	"github.com/aws/smithy-go/transport/http"
)

func TestParseTime(t *testing.T) {
	cases := map[string]struct {
		date    string
		expect  time.Time
		wantErr bool
	}{
		"with leading zero on day": {
			date:   "Fri, 05 Feb 2021 19:12:15 GMT",
			expect: time.Date(2021, 2, 5, 19, 12, 15, 0, time.UTC),
		},
		"without leading zero on day": {
			date:   "Fri, 5 Feb 2021 19:12:15 GMT",
			expect: time.Date(2021, 2, 5, 19, 12, 15, 0, time.UTC),
		},
		"with double digit day": {
			date:   "Fri, 15 Feb 2021 19:12:15 GMT",
			expect: time.Date(2021, 2, 15, 19, 12, 15, 0, time.UTC),
		},
		"RFC850": {
			date:   "Friday, 05-Feb-21 19:12:15 UTC",
			expect: time.Date(2021, 2, 5, 19, 12, 15, 0, time.UTC),
		},
		"ANSIC with leading zero on day": {
			date:   "Fri Feb 05 19:12:15 2021",
			expect: time.Date(2021, 2, 5, 19, 12, 15, 0, time.UTC),
		},
		"ANSIC without leading zero on day": {
			date:   "Fri Feb 5 19:12:15 2021",
			expect: time.Date(2021, 2, 5, 19, 12, 15, 0, time.UTC),
		},
		"ANSIC with double digit day": {
			date:   "Fri Feb 15 19:12:15 2021",
			expect: time.Date(2021, 2, 15, 19, 12, 15, 0, time.UTC),
		},
		"invalid time format": {
			date:    "1985-04-12T23:20:50.52Z",
			wantErr: true,
		},
	}

	for name, tt := range cases {
		t.Run(name, func(t *testing.T) {
			result, err := http.ParseTime(tt.date)
			if (err != nil) != tt.wantErr {
				t.Fatalf("error = %v, wantErr = %v", err, tt.wantErr)
			}
			if err != nil {
				return
			}
			if result.IsZero() {
				t.Fatalf("expected non-zero timestamp")
			}
			if tt.expect != result {
				t.Fatalf("expected '%s' got '%s'", tt.expect, result)
			}
		})
	}
}
