package waiter

import (
	"fmt"
	"math"
	"time"
)

// ComputeDelay will compute delay between waiter attempts using the min delay, max delay, remaining time and
// current attempt count.  The attempt count is the request call count.
// The zeroth attempt takes no delay.
func ComputeDelay(minDelay, maxDelay, remainingTime time.Duration, attempt int64) (time.Duration, error) {
	// validation
	if minDelay > maxDelay {
		return 0, fmt.Errorf("maximum delay must be greater than minimum delay")
	}

	// zeroth attempt, no delay
	if attempt <= 0 {
		return 0, nil
	}

	// remainingTime is zero or less, no delay
	if remainingTime <= 0 {
		return 0, nil
	}

	// [0.0, 1.0) * 2 ^ attempt-1
	ri := 1 << uint64(attempt-1)

	delay := time.Duration(math.Min(
		maxDelay.Seconds(),
		minDelay.Seconds()*float64(ri)),
	) * time.Second

	if remainingTime-delay <= minDelay {
		delay = remainingTime - minDelay
	}

	return delay, nil
}
