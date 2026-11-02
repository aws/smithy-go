package smithy

import (
	"sync/atomic"
	"unsafe"
)

// ExtensionID identifies a schema extension slot. Each codec family
// (JSON, CBOR, etc.) uses a distinct slot to cache precomputed data.
type ExtensionID int

const numExtensionSlots = 5

const (
	ExtJSON        ExtensionID = iota // transport/http/protocol/internal/json
	ExtCBOR                           // transport/http/protocol/internal/cbor
	ExtXML                            // transport/http/protocol/internal/xml
	ExtQuery                          // transport/http/protocol/internal/query
	ExtHTTPBinding                    // transport/http/protocol/internal/httpbinding
)

// SchemaExtension retrieves or lazily computes the extension for the given
// slot. build is called on first access for a schema and the result is cached.
// The build function must return a pointer to an immutable value.
func SchemaExtension[T any](s *Schema, id ExtensionID, build func(*Schema) *T) *T {
	var slot *unsafe.Pointer
	if m := s.m; m != nil {
		slot = &m.ext[id]
	} else {
		slot = &s.set.table(int(id)).slots[s.ord]
	}

	p := atomic.LoadPointer(slot)
	if p != nil {
		return (*T)(p)
	}
	return computeSchemaExtension(slot, s, build)
}

//go:noinline
func computeSchemaExtension[T any](slot *unsafe.Pointer, s *Schema, build func(*Schema) *T) *T {
	v := build(s)
	atomic.StorePointer(slot, unsafe.Pointer(v))
	return v
}
