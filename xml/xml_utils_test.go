package xml

import (
	"bytes"
	"io"
	"strings"
	"testing"
)

func TestGetResponseErrorCode(t *testing.T) {
	cases := map[string]struct {
		errorResponse          io.Reader
		noErrorWrappingEnabled bool
		expectedErrorCode      string
	}{
		"no error wrapping enabled": {
			errorResponse: bytes.NewReader([]byte(`<ErrorResponse>
    <Error>
        <Type>Sender</Type>
        <Code>InvalidGreeting</Code>
        <Message>Hi</Message>
        <AnotherSetting>setting</AnotherSetting>
    </Error>
    <RequestId>foo-id</RequestId>
</ErrorResponse>`)),
			expectedErrorCode: "InvalidGreeting",
		},
		"no error wrapping disabled": {
			errorResponse: bytes.NewReader([]byte(`<ErrorResponse>
    <Type>Sender</Type>
    <Code>InvalidGreeting</Code>
    <Message>Hi</Message>
    <AnotherSetting>setting</AnotherSetting>
    <RequestId>foo-id</RequestId>
</ErrorResponse>`)),
			noErrorWrappingEnabled: true,
			expectedErrorCode:      "InvalidGreeting",
		},
	}

	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			errorcode, err := GetResponseErrorCode(c.errorResponse, c.noErrorWrappingEnabled)
			if err != nil {
				t.Fatalf("expected no error, got %v", err)
			}

			if e, a := c.expectedErrorCode, errorcode; !strings.EqualFold(e, a) {
				t.Fatalf("expected %v, got %v", e, a)
			}
		})
	}
}
