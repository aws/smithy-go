package time

import (
	"testing"
	"time"
)

func TestDateTime(t *testing.T) {
	refTime := time.Date(1985, 4, 12, 23, 20, 0, int(50.52*float64(time.Second)), time.UTC)

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
	refTime := time.Date(2018, 1, 9, 20, 51, 0, int(21.123399936*float64(time.Second)), time.UTC)

	epochSeconds := FormatEpochSeconds(refTime)
	if e, a := 1515531081.1234, epochSeconds; e != a {
		t.Errorf("expected %v, got %v", e, a)
	}

	parseTime := ParseEpochSeconds(epochSeconds)

	if e, a := refTime, parseTime; !e.Equal(a) {
		t.Errorf("expected %v, got %v", e, a)
	}
}
