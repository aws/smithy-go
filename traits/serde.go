package traits

// JSONName represents smithy.api#jsonName.
type JSONName struct {
	Name string
}

// TraitID identifies the trait.
func (*JSONName) TraitID() string { return "smithy.api#jsonName" }

// MediaType represents smithy.api#mediaType.
type MediaType struct {
	Type string
}

// TraitID identifies the trait.
func (*MediaType) TraitID() string { return "smithy.api#mediaType" }

// TimestampFormat represents smithy.api#timestampFormat.
type TimestampFormat struct {
	Format string
}

// TraitID identifies the trait.
func (*TimestampFormat) TraitID() string { return "smithy.api#timestampFormat" }

// XMLAttribute represents smithy.api#xmlAttribute.
type XMLAttribute struct{}

// TraitID identifies the trait.
func (*XMLAttribute) TraitID() string { return "smithy.api#xmlAttribute" }

// XMLFlattened represents smithy.api#xmlFlattened.
type XMLFlattened struct{}

// TraitID identifies the trait.
func (*XMLFlattened) TraitID() string { return "smithy.api#xmlFlattened" }

// XMLName represents smithy.api#xmlName.
type XMLName struct {
	Name string
}

// TraitID identifies the trait.
func (*XMLName) TraitID() string { return "smithy.api#xmlName" }

// XMLNamespace represents smithy.api#xmlNamespace.
type XMLNamespace struct {
	URI    string
	Prefix string
}

// TraitID identifies the trait.
func (*XMLNamespace) TraitID() string { return "smithy.api#xmlNamespace" }
