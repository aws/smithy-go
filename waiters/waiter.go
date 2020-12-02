package waiters

import (
	"context"
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

// SleepWithContext will wait for the timer duration to expire, or the context
// is canceled. Which ever happens first. If the context is canceled the
// Context's error will be returned.
func SleepWithContext(ctx context.Context, dur time.Duration) error {
	t := time.NewTimer(dur)
	defer t.Stop()

	select {
	case <-t.C:
		break
	case <-ctx.Done():
		return ctx.Err()
	}

	return nil
}
