package time

import (
	"math"
	"strconv"
	"testing"
	"time"
)

func TestDateTime(t *testing.T) {
	refTime := time.Date(1985, 4, 12, 23, 20, 50, int(520*time.Millisecond), time.UTC)

	dateTime := FormatDateTime(refTime)
	if e, a := "1985-04-12T23:20:50.52Z", dateTime; e != a {
		t.Errorf("expected %v, got %v", e, a)
	}

	parseTime, err := ParseDateTime(dateTime)
	if err != nil {
		t.Fatalf("expected no error, got %v", err)
	}

	if e, a := refTime, parseTime; !e.Equal(a) {
		t.Errorf("expected %v, got %v", e, a)
	}
}

func TestHTTPDate(t *testing.T) {
	refTime := time.Date(2014, 4, 29, 18, 30, 38, 0, time.UTC)

	httpDate := FormatHTTPDate(refTime)
	if e, a := "Tue, 29 Apr 2014 18:30:38 GMT", httpDate; e != a {
		t.Errorf("expected %v, got %v", e, a)
	}

	parseTime, err := ParseHTTPDate(httpDate)
	if err != nil {
		t.Fatalf("expected no error, got %v", err)
	}

	if e, a := refTime, parseTime; !e.Equal(a) {
		t.Errorf("expected %v, got %v", e, a)
	}
}

func TestEpochSeconds(t *testing.T) {
	cases := []struct {
		reference    time.Time
		expectedUnix float64
		expectedTime time.Time
	}{
		{
			reference:    time.Date(2018, 1, 9, 20, 51, 21, 123399936, time.UTC),
			expectedUnix: 1515531081.123,
			expectedTime: time.Date(2018, 1, 9, 20, 51, 21, 1.23e8, time.UTC),
		},
		{
			reference:    time.Date(2018, 1, 9, 20, 51, 21, 1e8, time.UTC),
			expectedUnix: 1515531081.1,
			expectedTime: time.Date(2018, 1, 9, 20, 51, 21, 1e8, time.UTC),
		},
		{
			reference:    time.Date(2018, 1, 9, 20, 51, 21, 123567891, time.UTC),
			expectedUnix: 1515531081.123,
			expectedTime: time.Date(2018, 1, 9, 20, 51, 21, 1.23e8, time.UTC),
		},
		{
			reference:    time.Unix(0, math.MaxInt64).UTC(),
			expectedUnix: 9223372036.854,
			expectedTime: time.Date(2262, 04, 11, 23, 47, 16, 8.54e8, time.UTC),
		},
	}

	for i, tt := range cases {
		t.Run(strconv.Itoa(i), func(t *testing.T) {
			epochSeconds := FormatEpochSeconds(tt.reference)
			if e, a := tt.expectedUnix, epochSeconds; e != a {
				t.Errorf("expected %v, got %v", e, a)
			}

			parseTime := ParseEpochSeconds(epochSeconds)

			if e, a := tt.expectedTime, parseTime; !e.Equal(a) {
				t.Errorf("expected %v, got %v", e, a)
			}
		})
	}

	// Check an additional edge that higher precision values are truncated to milliseconds
	if e, a := time.Date(2018, 1, 9, 20, 51, 21, 1.23e8, time.UTC), ParseEpochSeconds(1515531081.12356); !e.Equal(a) {
		t.Errorf("expected %v, got %v", e, a)
	}
}
