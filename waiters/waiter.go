package waiters

import (
	"math"
	"time"
)

// ComputeDelay will compute delay between waiter attempts using the min delay, max delay, remaining time and
// current attempt count. Will return a computed delay.
func ComputeDelay(minDelay, maxDelay, remainingTime time.Duration, attempt int64) time.Duration {

	// [0.0, 1.0) * 2 ^ attempt-1
	ri := float64(1 << uint64(attempt-1))

	delay := time.Duration(math.Min(
		maxDelay.Seconds(),
		minDelay.Seconds()*ri),
	) * time.Second

	if remainingTime-delay <= minDelay {
		delay = remainingTime - minDelay
	}

	return delay
}
