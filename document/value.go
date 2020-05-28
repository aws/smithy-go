package document

import (
	"encoding/json"
	"fmt"
)

// Value provides an interface for encapsulating arbitrary Go values within an
// API protocol agnostic document.
type Value interface {
	// Attempts to unmarshal the document value into the Go type provided. Will
	// panic if the provided value is not a pointer type.
	UnmarshalDocument(interface{}) error

	// GetValue returns the underlying document value.
	GetValue() (interface{}, error)
}

// LazyValue provides a generic wrapper for Go values that are serialized into
// API parameters.
type LazyValue struct {
	Value interface{}
}

// NewLazyValue returns an initialized LazyValue wrapping the provided
// Go value.
func NewLazyValue(v interface{}) LazyValue {
	return LazyValue{
		Value: v,
	}
}

// UnmarshalDocument attempts to convert the wrapped value into the Go type
// provided.
//
// Will panic if the provided value is not a pointer type.
func (d LazyValue) UnmarshalDocument(t interface{}) error {
	// TODO need document type generic unmarshaling behavior.

	blob, err := json.Marshal(d.Value)
	if err != nil {
		return fmt.Errorf("unable to convert document value, %w", err)
	}

	if err := json.Unmarshal(blob, t); err != nil {
		return fmt.Errorf("unable to convert document value, %w", err)
	}

	return nil
}

// GetValue returns the underlying document value.
func (d LazyValue) GetValue() (interface{}, error) {
	return d.Value, nil
}
