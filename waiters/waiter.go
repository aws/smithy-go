package waiters

import (
	"math"
	"time"
)

// ComputeDelay will compute delay between waiter attempts using the min delay, max delay, remaining time and
// current attempt count. Will return a delay value in seconds.
func ComputeDelay(minDelay, maxDelay, remainingTime time.Duration, attempt int64) time.Duration {
	delay := time.Duration(math.Min(
		maxDelay.Seconds(),
		minDelay.Seconds()*math.Pow(2, float64(attempt-1))),
	) * time.Second

	if remainingTime-delay <= minDelay {
		delay = remainingTime - minDelay
	}

	return delay
}
