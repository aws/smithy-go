package smithy

import (
	"sync"
	"sync/atomic"
	"testing"
)

type testDeserializableError struct{}

func (*testDeserializableError) Error() string                       { return "" }
func (*testDeserializableError) Deserialize(ShapeDeserializer) error { return nil }

func TestLazyTypeRegistry(t *testing.T) {
	var calls atomic.Int32
	registry := NewLazyTypeRegistry(func() map[string]*TypeRegistryEntry {
		calls.Add(1)
		return map[string]*TypeRegistryEntry{
			"com.example#Boom": {New: func() any { return &testDeserializableError{} }},
		}
	})

	if n := calls.Load(); n != 0 {
		t.Fatalf("entries created before first lookup: %d calls", n)
	}

	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, ok := registry.LookupEntry("com.example#Boom"); !ok {
				t.Error("expected entry by full ID")
			}
			if _, ok := registry.DeserializableError("Boom"); !ok {
				t.Error("expected error by short name")
			}
			if _, ok := registry.LookupEntry("com.example#Missing"); ok {
				t.Error("unexpected entry")
			}
		}()
	}
	wg.Wait()

	if n := calls.Load(); n != 1 {
		t.Errorf("expected entries to be created once, got %d calls", n)
	}
}

func TestEagerTypeRegistry(t *testing.T) {
	registry := &TypeRegistry{Entries: map[string]*TypeRegistryEntry{
		"com.example#Boom": {New: func() any { return &testDeserializableError{} }},
	}}

	if _, ok := registry.DeserializableError("com.example#Boom"); !ok {
		t.Error("expected error by full ID")
	}
}
