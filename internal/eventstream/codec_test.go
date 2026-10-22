package eventstream

import (
	"bytes"
	"fmt"
	"sync"
	"testing"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/eventstream"
)

func TestCodecConcurrentDeserialization(t *testing.T) {
	var initial bytes.Buffer
	var initialMessage eventstream.Message
	initialMessage.Headers.Set(eventstream.EventTypeHeader, eventstream.StringValue("initial-response"))
	if err := eventstream.NewEncoder().Encode(&initial, initialMessage); err != nil {
		t.Fatal(err)
	}
	schema := smithy.NewSchema(smithy.ShapeID{Namespace: "test", Name: "Events"}, smithy.ShapeTypeUnion, 0)
	codec := &Codec{}
	start := make(chan struct{})
	var wg sync.WaitGroup
	for i := 0; i < 32; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			var encoded bytes.Buffer
			message := eventstream.Message{Payload: []byte(fmt.Sprintf("payload-%d", i))}
			message.Headers.Set(eventstream.MessageTypeHeader, eventstream.StringValue(eventstream.EventMessageType))
			message.Headers.Set(eventstream.EventTypeHeader, eventstream.StringValue("FutureEvent"))
			if err := eventstream.NewEncoder().Encode(&encoded, message); err != nil {
				t.Error(err)
				return
			}
			<-start
			for j := 0; j < 20; j++ {
				if err := codec.DeserializeInitialResponse(nil, bytes.NewReader(initial.Bytes()), nil); err != nil {
					t.Error(err)
					return
				}
				value, err := codec.DeserializeEventMessage(schema, nil, bytes.NewReader(encoded.Bytes()))
				if err != nil {
					t.Error(err)
					return
				}
				unknown, ok := value.(*eventstream.UnknownUnionMember)
				if !ok || unknown.Tag != "FutureEvent" || !bytes.Equal(unknown.Value, encoded.Bytes()) {
					t.Errorf("unknown event did not preserve its tag and encoded message: %v", value)
					return
				}
			}
		}(i)
	}
	close(start)
	wg.Wait()
}
