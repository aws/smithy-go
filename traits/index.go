package traits

// Trait index constants, ordered by frequency of occurrence across AWS API
// models. Lower indices are assigned to more common traits so that the
// per-schema indexed slice stays small.
const (
	IndexJSONName = iota
	IndexHTTP
	IndexHTTPLabel
	IndexXMLName
	IndexHTTPQuery
	IndexEC2QueryName
	IndexHTTPError
	IndexHTTPHeader
	IndexSensitive
	IndexAWSQueryError
	IndexTimestampFormat
	IndexHTTPPayload
	IndexContextParam
	IndexHTTPResponseCode
	IndexHostLabel
	IndexXMLNamespace
	IndexXMLFlattened
	IndexStreaming
	IndexMediaType
	IndexHTTPQueryParams
	IndexEventPayload
	IndexHTTPPrefixHeaders
	IndexEventHeader
	IndexXMLAttribute
	IndexUnitShape

	// Count is the total number of indexed traits.
	Count
)

// TraitIndex implements [smithy.IndexableTrait].
func (*JSONName) TraitIndex() int { return IndexJSONName }

// TraitIndex implements [smithy.IndexableTrait].
func (*HTTP) TraitIndex() int { return IndexHTTP }

// TraitIndex implements [smithy.IndexableTrait].
func (*HTTPLabel) TraitIndex() int { return IndexHTTPLabel }

// TraitIndex implements [smithy.IndexableTrait].
func (*XMLName) TraitIndex() int { return IndexXMLName }

// TraitIndex implements [smithy.IndexableTrait].
func (*HTTPQuery) TraitIndex() int { return IndexHTTPQuery }

// TraitIndex implements [smithy.IndexableTrait].
func (*EC2QueryName) TraitIndex() int { return IndexEC2QueryName }

// TraitIndex implements [smithy.IndexableTrait].
func (*HTTPError) TraitIndex() int { return IndexHTTPError }

// TraitIndex implements [smithy.IndexableTrait].
func (*HTTPHeader) TraitIndex() int { return IndexHTTPHeader }

// TraitIndex implements [smithy.IndexableTrait].
func (*Sensitive) TraitIndex() int { return IndexSensitive }

// TraitIndex implements [smithy.IndexableTrait].
func (*AWSQueryError) TraitIndex() int { return IndexAWSQueryError }

// TraitIndex implements [smithy.IndexableTrait].
func (*TimestampFormat) TraitIndex() int { return IndexTimestampFormat }

// TraitIndex implements [smithy.IndexableTrait].
func (*HTTPPayload) TraitIndex() int { return IndexHTTPPayload }

// TraitIndex implements [smithy.IndexableTrait].
func (*ContextParam) TraitIndex() int { return IndexContextParam }

// TraitIndex implements [smithy.IndexableTrait].
func (*HTTPResponseCode) TraitIndex() int { return IndexHTTPResponseCode }

// TraitIndex implements [smithy.IndexableTrait].
func (*HostLabel) TraitIndex() int { return IndexHostLabel }

// TraitIndex implements [smithy.IndexableTrait].
func (*XMLNamespace) TraitIndex() int { return IndexXMLNamespace }

// TraitIndex implements [smithy.IndexableTrait].
func (*XMLFlattened) TraitIndex() int { return IndexXMLFlattened }

// TraitIndex implements [smithy.IndexableTrait].
func (*Streaming) TraitIndex() int { return IndexStreaming }

// TraitIndex implements [smithy.IndexableTrait].
func (*MediaType) TraitIndex() int { return IndexMediaType }

// TraitIndex implements [smithy.IndexableTrait].
func (*HTTPQueryParams) TraitIndex() int { return IndexHTTPQueryParams }

// TraitIndex implements [smithy.IndexableTrait].
func (*EventPayload) TraitIndex() int { return IndexEventPayload }

// TraitIndex implements [smithy.IndexableTrait].
func (*HTTPPrefixHeaders) TraitIndex() int { return IndexHTTPPrefixHeaders }

// TraitIndex implements [smithy.IndexableTrait].
func (*EventHeader) TraitIndex() int { return IndexEventHeader }

// TraitIndex implements [smithy.IndexableTrait].
func (*XMLAttribute) TraitIndex() int { return IndexXMLAttribute }

// TraitIndex implements [smithy.IndexableTrait].
func (*UnitShape) TraitIndex() int { return IndexUnitShape }
