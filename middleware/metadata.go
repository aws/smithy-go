package middleware

import "github.com/aws/smithy-go"

// MetadataReader provides an interface for reading metadata from the
// underlying metadata container.
type MetadataReader = smithy.PropertiesReader

// Metadata provides storing and reading metadata values. Keys may be any
// comparable value type. Get and set will panic if key is not a comparable
// value type.
//
// Metadata uses lazy initialization, and Set method must be called as an
// addressable value, or pointer. Not doing so may cause key/value pair to not
// be set.
type Metadata = smithy.Properties
