package smithy

import (
	"sync/atomic"
	"unsafe"
)

// ExtensionID identifies a schema extension slot. Each codec family
// (JSON, CBOR, etc.) uses a distinct slot to cache precomputed data.
type ExtensionID int

const numExtensionSlots = 4

const (
	ExtJSON  ExtensionID = iota // aws-protocols/internal/json
	ExtCBOR                     // smithy-http-protocols/internal/cbor
	ExtXML                      // aws-protocols/internal/xml
	ExtQuery                    // aws-protocols/internal/query
)

// SchemaExtension retrieves or lazily computes the extension for the given
// slot. build is called on first access for a schema and the result is cached.
// The build function must return a pointer to an immutable value.
func SchemaExtension[T any](s *Schema, id ExtensionID, build func(*Schema) *T) *T {
	p := atomic.LoadPointer(&s.ext[id])
	if p != nil {
		return (*T)(p)
	}
	return computeSchemaExtension(s, id, build)
}

//go:noinline
func computeSchemaExtension[T any](s *Schema, id ExtensionID, build func(*Schema) *T) *T {
	v := build(s)
	atomic.StorePointer(&s.ext[id], unsafe.Pointer(v))
	return v
}
