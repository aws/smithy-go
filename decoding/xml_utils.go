package decoding

import (
	"encoding/xml"
	"fmt"
	"io"
	"io/ioutil"
)

// TODO: write tests for this util func

// GetXMLResponseErrorCode returns the error code from an xml error response body
func GetXMLResponseErrorCode(r io.Reader, noErrorWrapping bool) (string, error) {
	rb, err := ioutil.ReadAll(r)
	if err != nil {
		return "", err
	}

	if noErrorWrapping {
		var errResponse errorWrapper
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

type errorResponse struct {
	Err errorWrapper `xml:"Error"`
}

type errorWrapper struct {
	Code string `xml:"Code"`
}
