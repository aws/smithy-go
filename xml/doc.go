// Package xml usage guidelines:
//
// Value is responsible for writing start element xml tags.
//
// MemberElement should be used to build structure and simple member elements.
// CollectionElement should be used to build collection shape elements such as map, array.
//
// This utility is written in accordance to our design to delegate to shape serializer function
// in which a xml.Value will be passed around.
//
// Resources followed: https://awslabs.github.io/smithy/1.0/spec/core/xml-traits.html#
package xml
