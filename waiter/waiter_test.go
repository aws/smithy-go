package waiter

import (
	"strings"
	"testing"
	"time"
)

func TestComputeDelay(t *testing.T) {
	cases := map[string]struct {
		totalAttempts  int64
		minDelay       time.Duration
		maxDelay       time.Duration
		maxWaitTime    time.Duration
		expectedDelays []time.Duration
		expectedError  string
	}{
		"standard": {
			totalAttempts:  8,
			minDelay:       2 * time.Second,
			maxDelay:       120 * time.Second,
			maxWaitTime:    300 * time.Second,
			expectedDelays: []time.Duration{2, 4, 8, 16, 32, 64, 120, 52},
		},
		"zero minDelay": {
			totalAttempts:  3,
			minDelay:       0,
			maxDelay:       120 * time.Second,
			maxWaitTime:    300 * time.Second,
			expectedDelays: []time.Duration{0, 0, 0},
		},
		"zero maxDelay": {
			totalAttempts:  3,
			minDelay:       10 * time.Second,
			maxDelay:       0,
			maxWaitTime:    300 * time.Second,
			expectedDelays: []time.Duration{0, 0, 0},
			expectedError:  "maximum delay must be greater than minimum delay",
		},
		"zero remaining time": {
			totalAttempts:  3,
			minDelay:       10 * time.Second,
			maxWaitTime:    0,
			expectedDelays: []time.Duration{0, 0, 0},
		},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			var err error
			var attempt int64
			var delays = make([]time.Duration, c.totalAttempts)

			remainingTime := c.maxWaitTime

			for {
				attempt++

				if c.totalAttempts < attempt {
					break
				}

				if remainingTime <= 0 {
					break
				}

				delay, e := ComputeDelay(c.minDelay, c.maxDelay, remainingTime, attempt)
				if e != nil {
					err = e
					break
				}
				delays[attempt-1] = delay

				remainingTime -= delay
			}

			if len(c.expectedError) != 0 {
				if err == nil {
					t.Fatalf("expected error, got none")
				}
				if e, a := c.expectedError, err.Error(); !strings.Contains(a, e) {
					t.Fatalf("expected error %v, got %v instead", e, a)
				}
			} else if err != nil {
				t.Fatalf("expected no error, got %v", err)
			}

			for i, expectedDelay := range c.expectedDelays {
				if e, a := expectedDelay*time.Second, delays[i]; e != a {
					t.Fatalf("attempt %d : expected delay to be %v, got %v", i+1, e, a)
				}
			}
		})
	}
}
