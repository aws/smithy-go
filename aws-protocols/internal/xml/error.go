package xml

import "io"

// GetProtocolErrorInfo extracts Smithy error details from a query-protocol
// response.
//
// This version of GetProtocolErrorInfo also handles the extraction of the
// modeled error body from the protocol wrapper so the caller can operate on it
// directly.
func GetProtocolErrorInfo(payload []byte) (code, message string, errorBody []byte, err error) {
	code = "UnknownError"
	message = code

	errorBody, err = ExtractElement(payload, "Error", true)
	if err != nil || len(errorBody) == 0 {
		return
	}

	// we are in a fragment here so just leverage ExtractElement to get the
	// inner XML of these, instead of having to wrap in a tag to pass to the
	// stdlib decoder
	c, err := ExtractElement(errorBody, "Code", false)
	if err != nil && err != io.EOF {
		return
	}
	m, err := ExtractElement(errorBody, "Message", false)
	if err != nil && err != io.EOF {
		return
	}

    // clear err if it was io.EOF
	err = nil
	if len(c) > 0 {
		code = string(c)
	}
	if len(m) > 0 {
		message = string(m)
	}
	return
}
