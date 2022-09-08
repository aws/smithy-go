package transport

import "strings"

// Fields is a collection Field values.
type Fields struct {
	fields []Field
}

// Get retrieves a Field by name, by performing a case-insensitive lookup.
func (f *Fields) Get(name string) Field {
	if i, ok := f.findField(name); ok {
		return f.fields[i]
	}
	return NewField(name)
}

// Has returns whether a matching Field exists for the given name using case-insensitive lookup.
func (f *Fields) Has(name string) bool {
	_, ok := f.findField(name)
	return ok
}

func (f *Fields) findField(name string) (int, bool) {
	for i := range f.fields {
		if !strings.EqualFold(f.fields[i].name, name) {
			continue
		}
		return i, true
	}
	return 0, false
}

// Set adds field to set of Fields. If a field with the same name exists (case-insensitive matching), that field's
// is replaced with the values from the provided field, with the casing of the name remaining the same.
// If the field doesn't exist, then it will be added. Returns the old field and true if the field already existed,
// otherwise returns false.
func (f *Fields) Set(field Field) (old Field, ok bool) {
	if i, ok := f.findField(field.name); ok {
		old := f.fields[i]
		field.name = old.Name() // maintain the old name casing
		f.fields[i] = field
		return old, true
	}
	// field needs to be added
	f.fields = append(f.fields, field)
	return NewField(field.name), false
}

// Remove searches the fields for a field matching the provided name (case-insensitive), and removes the field if
// found. Returns the old field and true if the field was removed, otherwise returns false.
func (f *Fields) Remove(name string) (old Field, ok bool) {
	i, ok := f.findField(name)
	if !ok {
		return NewField(name), false
	}
	old = f.fields[i]
	f.fields = append(f.fields[:i], f.fields[i+1:]...)
	return old, true
}

// Field is a type representing a field name, and its associated values.
type Field struct {
	name   string
	values []string
}

// NewField constructs a new Field with the given name and values.
func NewField(name string, values ...string) Field {
	return Field{
		name:   name,
		values: values,
	}
}

// Name returns the field name
func (f Field) Name() string {
	return f.name
}

// Add appends the given values to the field.
func (f Field) Add(values ...string) Field {
	if len(values) == 0 {
		return f
	}
	orig := f.values
	f.values = make([]string, len(orig), len(orig)+len(values))
	copy(f.values, orig)
	f.values = append(f.values, values...)
	return f
}

// Set sets the field to have the given values.
func (f Field) Set(values ...string) Field {
	if len(values) == 0 {
		return f.Clear()
	}
	f.values = make([]string, len(values))
	copy(f.values, values)
	return f
}

// Clear clears the values set on the field.
func (f Field) Clear() Field {
	f.values = nil
	return f
}

// HasValues returns whether the field has any values.
func (f Field) HasValues() bool {
	return len(f.values) > 0
}

// Values returns a copy of the fields current values.
func (f Field) Values() (values []string) {
	if len(f.values) == 0 {
		return nil
	}
	values = make([]string, len(f.values))
	copy(values, f.values)
	return values
}
