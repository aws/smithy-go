package waiter

import (
	"fmt"
	"math"
	"math/rand"
	"time"

	smithytime "github.com/awslabs/smithy-go/time"
)

// ComputeDelay computes delay between waiter attempts. The function takes in a current attempt count,
// minimum delay, maximum delay, and remaining wait time for waiter as input.
//
// Returns the computed delay and if next attempt count is possible within the given input time constraints.
// Note that the zeroth attempt results in no delay.
func ComputeDelay(attempt int64, minDelay, maxDelay, remainingTime time.Duration) (delay time.Duration, done bool, err error) {
	// validation
	if minDelay > maxDelay {
		return 0, true, fmt.Errorf("maximum delay must be greater than minimum delay")
	}

	// zeroth attempt, no delay
	if attempt <= 0 {
		return 0, true, nil
	}

	// remainingTime is zero or less, no delay
	if remainingTime <= 0 {
		return 0, true, nil
	}

	// as we use log, ensure min delay and maxdelay are atleast 1 ns
	if minDelay < 1 {
		minDelay = 1
	}

	// if max delay is less than 1 ns, return 0 as delay
	if maxDelay < 1 {
		return 0, true, nil
	}

	// check if this is the last attempt possible and compute delay accordingly
	defer func() {
		if remainingTime-delay <= minDelay {
			delay = remainingTime - minDelay
			done = true
		}
	}()

	// Get attempt ceiling to prevent integer overflow.
	attemptCeiling := (math.Log(float64(maxDelay/minDelay)) / math.Log(2)) + 1

	if attempt > int64(attemptCeiling) {
		delay = maxDelay
	} else {
		// Compute exponential delay based on attempt.
		// [0.0, 1.0) * 2 ^ attempt-1
		ri := 1 << uint64(attempt-1)
		// compute delay
		delay = smithytime.DurationMin(maxDelay, minDelay*time.Duration(ri))
	}

	if delay != minDelay {
		// randomize to get jitter between min delay and delay value
		delay = time.Duration(rand.Int63n(int64(delay-minDelay))) + minDelay
	}

	return delay, done, nil
}
