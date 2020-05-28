package time

import (
	"time"
)

const (
	// dateTimeFormat is a IMF-fixdate formatted time https://tools.ietf.org/html/rfc7231.html#section-7.1.1.1
	dateTimeFormat = "2006-01-02T15:04:05.99Z"

	// httpDateFormat is a date time defined by RFC3339 section 5.6 with no UTC offset.
	httpDateFormat = "Mon, 02 Jan 2006 15:04:05 GMT"
)

// FormatDateTime format value as a date-time
func FormatDateTime(value time.Time) string {
	return value.Format(dateTimeFormat)
}

// ParseDateTimeFormat parse a string as a date-time
func ParseDateTimeFormat(value string) (time.Time, error) {
	return time.Parse(dateTimeFormat, value)
}

// FormatHTTPDate format value as a http-date
func FormatHTTPDate(value time.Time) string {
	return value.Format(httpDateFormat)
}

// ParseHTTPDate parse a string as a http-date
func ParseHTTPDate(value string) (time.Time, error) {
	return time.Parse(httpDateFormat, value)
}

// FormatEpochSeconds returns value as a Unix time in seconds with with decimal precision
func FormatEpochSeconds(value time.Time) float64 {
	return float64(value.UnixNano()) / float64(time.Second)
}

// ParseEpochSeconds returns value as a Unix time in seconds with with decimal precision
func ParseEpochSeconds(value float64) time.Time {
	return time.Unix(0, int64(value*float64(time.Second)))
}
