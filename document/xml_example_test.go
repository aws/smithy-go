package document_test

import (
	"fmt"
	"log"

	"github.com/awslabs/smithy-go/document"
)

func ExampleMarshalXMLDocument() {
	type Input struct {
		ADoc document.Unmarshaler
	}

	myType := struct {
		Abc string
	}{
		Abc: "123",
	}
	jsonDoc, err := document.MarshalXMLDocument(myType)
	if err != nil {
		log.Fatalf("unable to marshal XML document, %v", err)
	}

	params := &Input{
		ADoc: jsonDoc,
	}

	client.AnAPIOperation(params)
}

func ExampleRawXML_UnmarshalDocument() {
	type Output struct {
		ADoc document.Unmarshaler
	}

	v := Output{ADoc: document.RawXML([]byte(`<top><A>cool document</A></top>`))}

	var myType struct {
		A string
	}
	if err := v.ADoc.UnmarshalDocument(&myType); err != nil {
		log.Fatalf("unable to unmarshal document, %v", err)
	}

	fmt.Printf("A: %v\n", myType.A)

	// Output:
	// A: cool document
}

type mockClient struct{}

func (mockClient) AnAPIOperation(interface{}) {}

var client mockClient
