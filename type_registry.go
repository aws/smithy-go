package smithy

import (
	"strings"
	"sync"
)

// TypeRegistry creates an instance of a type based on its Smithy IDL shape ID.
//
// Generated clients have an exported package-level registry (named
// TypeRegistry) that holds all structure types for the service.
type TypeRegistry struct {
	// For a registry created with [NewLazyTypeRegistry] this is nil until the
	// first lookup, so use [TypeRegistry.LookupEntry] to read entries.
	Entries map[string]*TypeRegistryEntry

	lazy func() map[string]*TypeRegistryEntry
	once sync.Once
}

// NewLazyTypeRegistry creates a TypeRegistry whose entries are produced by
// calling fn the first time the registry is used. fn is called at most once.
func NewLazyTypeRegistry(fn func() map[string]*TypeRegistryEntry) *TypeRegistry {
	return &TypeRegistry{lazy: fn}
}

func (t *TypeRegistry) entries() map[string]*TypeRegistryEntry {
	if t.lazy == nil {
		return t.Entries
	}

	t.once.Do(func() { t.Entries = t.lazy() })
	return t.Entries
}

// RegistryEntry creates a type registry entry.
func RegistryEntry[T any](schema *Schema) *TypeRegistryEntry {
	return &TypeRegistryEntry{
		Schema: schema,
		New: func() any {
			return new(T)
		},
	}
}

// DeserializableError provides an instance of a deserializable error structure
// for a given shape ID.
//
// The ID is given as a string here since this will be called in a context where
// a shape ID is a discriminator read in from some wire payload.
func (t *TypeRegistry) DeserializableError(id string) (DeserializableError, bool) {
	return typeRegistryLookup[DeserializableError](t, id)
}

// LookupEntry returns the registry entry for the given shape ID.
func (t *TypeRegistry) LookupEntry(id string) (*TypeRegistryEntry, bool) {
	entry, ok := t.entries()[id]
	if !ok {
		entry, ok = t.lookupShortName(id)
	}
	return entry, ok
}

// TypeRegistryEntry holds the schema and constructor for a registered shape.
type TypeRegistryEntry struct {
	Schema *Schema
	New    func() any
}

func (t *TypeRegistry) lookupShortName(id string) (*TypeRegistryEntry, bool) {
	for key, e := range t.entries() {
		if idx := strings.Index(key, "#"); idx != -1 && key[idx+1:] == id {
			return e, true
		}
	}
	return nil, false
}

func typeRegistryLookup[T any](t *TypeRegistry, id string) (T, bool) {
	entry, ok := t.LookupEntry(id)
	if !ok {
		var v T
		return v, false
	}

	v, ok := entry.New().(T)
	return v, ok
}
