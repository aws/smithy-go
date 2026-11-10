package traits

import (
	"testing"

	smithy "github.com/aws/smithy-go"
)

// The generated static schemas (see DefaultTraitGenerators.java in the
// codegen) place traits by these indexes, so they are part of the contract
// between the generator and the runtime. Changing one requires regenerating
// every client.
func TestTraitIndexesAreStable(t *testing.T) {
	for want, trait := range []smithy.IndexableTrait{
		&JSONName{}, &HTTP{}, &HTTPLabel{}, &XMLName{}, &HTTPQuery{}, &EC2QueryName{},
		&HTTPError{}, &HTTPHeader{}, &Sensitive{}, &AWSQueryError{}, &TimestampFormat{},
		&HTTPPayload{}, &ContextParam{}, &HTTPResponseCode{}, &HostLabel{}, &XMLNamespace{},
		&XMLFlattened{}, &Streaming{}, &MediaType{}, &HTTPQueryParams{}, &EventPayload{},
		&HTTPPrefixHeaders{}, &EventHeader{}, &XMLAttribute{}, &UnitShape{},
	} {
		if got := trait.TraitIndex(); got != want {
			t.Errorf("%T index = %d, want %d", trait, got, want)
		}
	}
}
