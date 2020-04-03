package middleware

// Metadata provides an interface for storing and reading metadata values. Keys
// may be any comparable value type. Get and set will panic if key is not a
// comparable value type.
type Metadata interface {
	Get(key interface{}) interface{}
	Set(Key, value interface{})
}

// MetadataReader provides an interface for reading metadata from the
// underlying metadata container.
type MetadataReader interface {
	Get(key interface{}) interface{}
}

// NewMetadata returns an initialized value for storing metadata on.
func NewMetadata() Metadata {
	return &metadata{
		values: map[interface{}]interface{}{},
	}
}

type metadata struct {
	values map[interface{}]interface{}
}

// Get attempts to retrieve the value the key points to. Returns nil if the
// key was not found.
//
//Panics if key type is not comparable.
func (m *metadata) Get(key interface{}) interface{} {
	return m.values[key]
}

// Set stores the value pointed to by the key. If a value already exists at
// that key it will be replaced with the new value.
//
// Panics if the key type is not comparable.
func (m *metadata) Set(key, value interface{}) {
	m.values[key] = value
}

// Has returns if the key exists in the metadata.
//
// Panics if the key type is not comparable.
func (m *metadata) Has(key interface{}) bool {
	_, ok := m.values[key]
	return ok
}
