// Package xml usage guidelines:
//
// Value is responsible for writing start element xml tags.
// Concrete types such as NestedElement, Map, Array that return a Object type must be closed.
// The close should ideally be called as a defer statement.
//
// This utility is written in accordance to our design to delegate to shape serializer function
// in which a xml.Value will be passed around.
//
// Resources followed: https://awslabs.github.io/smithy/1.0/spec/core/xml-traits.html#
//
package xml
