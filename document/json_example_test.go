package document_test

import (
	"fmt"
	"log"

	"github.com/awslabs/smithy-go/document"
)

func ExampleMarshalJSONDocument() {
	type Input struct {
		ADoc document.Unmarshaler
	}

	myType := struct {
		Abc string
	}{
		Abc: "123",
	}
	jsonDoc, err := document.MarshalJSONDocument(myType)
	if err != nil {
		log.Fatalf("unable to marshal JSON document, %v", err)
	}

	params := &Input{
		ADoc: jsonDoc,
	}

	client.AnAPIOperation(params)
}

func ExampleRawJSON_UnmarshalDocument() {
	type Output struct {
		ADoc document.Unmarshaler
	}

	v := Output{ADoc: document.RawJSON([]byte(`{"A":"cool document"}`))}

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