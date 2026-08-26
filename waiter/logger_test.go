package waiter

import (
	"testing"

	"github.com/aws/smithy-go/middleware"
)

// TestAddLogger verifies that AddLogger can be inserted into a bare
// Initialize step without depending on the presence of any other
// middleware. AddLogger previously anchored itself relative to a
// "SetLogger" middleware that stopped being registered in the Initialize
// step, which made every AddLogger call fail with a "not found: SetLogger"
// error (surfaced to callers as "not found: WaiterLogger" since Insert
// returns the id of the caller). See waiter.Logger.AddLogger.
func TestAddLogger(t *testing.T) {
	stack := middleware.NewStack("test", func() interface{} { return struct{}{} })

	var logger Logger
	if err := logger.AddLogger(stack); err != nil {
		t.Fatalf("expected AddLogger to succeed on an empty Initialize step, got error: %v", err)
	}

	ids := stack.Initialize.List()
	found := false
	for _, id := range ids {
		if id == (&Logger{}).ID() {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("expected %q to be registered in the Initialize step, got: %v", (&Logger{}).ID(), ids)
	}
}
