package waiters

import (
	"context"
	"fmt"
	"math"
	"time"

	"github.com/awslabs/smithy-go/logging"
	"github.com/awslabs/smithy-go/middleware"
)

// WaiterRetrier represents waiter retrier middleware struct
type WaiterRetrier struct {

	// MinDelay is the minimum amount of time to delay between retries in seconds
	MinDelay           time.Duration

	// MaxDelay is the maximum amount of time to delay between retries in seconds.
	MaxDelay           time.Duration

	// MaxWaitTime is the maximum amount of wait time before a waiter is forced to return.
	MaxWaitTime        time.Duration

	// MaxAttempts is the maximum number of attempts to fetch a success/failure waiter state.
	MaxAttempts        int64

	// Enable logger for logging waiter workflow
	EnableLogger       bool

	// WaiterStateMutator is mutator function option that can be used to override the
	// service defined waiter-behavior based on operation output, or returned error.
	// The mutator function is used by the waiter to decide if a state is retryable
	// or a terminal state.
	//
	// This option is by default backfilled, to use service-modeled waiter state mutators.
	// This option can thus be used to define a custom waiter state with fall-back to
	// service-modeled waiter state mutators.
	WaiterStateMutator func(ctx context.Context, input, output interface{}, err error) (bool, error)

	// RequestCloner function is a transport-agnositic accessor function that is intended to take in a request,
	// and return a cloned request
	RequestCloner func(interface{}) interface{}
}

// ID is the waiterRetrier middleware identifier
func (*WaiterRetrier) ID() string {
	return "WaiterRetrier"
}

// HandleFinalize handles the finalize middleware step
func (m *WaiterRetrier) HandleFinalize(
	ctx context.Context, in middleware.FinalizeInput, next middleware.FinalizeHandler,
) (
	out middleware.FinalizeOutput, metadata middleware.Metadata, err error,
) {
	// fetch logger
	logger := middleware.GetLogger(ctx)

	// current attempt, delay
	var attempt int64
	var delay time.Duration
	var remainingTime = m.MaxWaitTime

	for {

		// check number of attempts
		if m.MaxAttempts == attempt {
			break
		}

		attempt++

		attemptInput := in
		attemptInput.Request = m.RequestCloner(attemptInput.Request)

		if attempt > 1 {
			// compute exponential backoff between retries
			delay = computeDelay(m.MinDelay, m.MaxDelay, remainingTime, attempt)
			// update the remaining time
			remainingTime = remainingTime - delay

			// rewind transport stream
			if rewindable, ok := in.Request.(interface{ RewindStream() error }); ok {
				if err := rewindable.RewindStream(); err != nil {
					return out, metadata, fmt.Errorf("failed to rewind transport stream for retry, %w", err)
				}
			}

			// sleep for the delay amount before invoking a request
			if err = sleepWithContext(ctx, delay); err != nil {
				return out, metadata, fmt.Errorf("request cancelled while waiting, %w", err)
			}

			// log retry attempt
			if m.EnableLogger {
				logger.Logf(logging.Debug, fmt.Sprintf("retrying waiter request, attempt count: %d", attempt))
			}
		}

		// attempt request
		out, metadata, err = next.HandleFinalize(ctx, attemptInput)

		// check for state mutation
		retryable, err := m.WaiterStateMutator(ctx, attemptInput, out, err)
		if !retryable || err != nil {
			if m.EnableLogger {
				if err != nil {
					logger.Logf(logging.Debug, "waiter transitioned to a failed state with unretryable error %v", err)
				} else {
					logger.Logf(logging.Debug, "waiter transitioned to a success state")
				}
			}
			return out, metadata, err
		}
	}

	if m.EnableLogger {
		logger.Logf(logging.Debug, "max retry attempts exhausted, max %d", attempt)
	}
	return out, metadata, fmt.Errorf("exhausted all retry attempts while waiting for the resource state")
}

// computeDelay will compute delay between waiter attempts using the min delay, max delay, remaining time and
// current attempt count. Will return a delay value in seconds.
func computeDelay(minDelay, maxDelay, remainingTime time.Duration, attempt int64) time.Duration {
	delay := time.Duration(math.Min(
		maxDelay.Seconds(),
		minDelay.Seconds()*math.Pow(2, float64(attempt-1))),
	) * time.Second

	if remainingTime-delay <= minDelay {
		delay = remainingTime - minDelay
	}

	return delay
}

// sleepWithContext will wait for the timer duration to expire, or the context
// is canceled. Which ever happens first. If the context is canceled the
// Context's error will be returned.
func sleepWithContext(ctx context.Context, dur time.Duration) error {
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
