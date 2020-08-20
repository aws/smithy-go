package xml

import (
	"encoding/xml"
	"fmt"
	"io"
	"io/ioutil"
)

// GetResponseErrorCode returns the error code from an xml error response body
func GetResponseErrorCode(r io.Reader, noErrorWrapping bool) (string, error) {
	rb, err := ioutil.ReadAll(r)
	if err != nil {
		return "", err
	}

	if noErrorWrapping {
		var errResponse errorBody
		err := xml.Unmarshal(rb, &errResponse)
		if err != nil {
			return "", fmt.Errorf("error while fetching xml error response code: %w", err)
		}
		return errResponse.Code, err
	}

	var errResponse errorResponse
	if err := xml.Unmarshal(rb, &errResponse); err != nil {
		return "", fmt.Errorf("error while fetching xml error response code: %w", err)
	}
	return errResponse.Err.Code, nil
}

// errorResponse represents the outer error response body
// i.e. <ErrorResponse>...</ErrorResponse>
type errorResponse struct {
	Err errorBody `xml:"Error"`
}

// errorBody represents the inner error body is wrapped by <ErrorResponse> tag
// eg. if error response is <ErrorResponse><Error>...</Error><ErrorResponse>
// here errorBody represents <Error>...</Error>
type errorBody struct {
	Code string `xml:"Code"`
}
