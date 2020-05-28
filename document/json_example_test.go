package document_test

import (
	"fmt"
	"log"

	"github.com/awslabs/smithy-go/document"
)

var _ document.Value = document.LazyValue{}
var _ document.Value = document.LazyJSONValue{}
var _ document.Value = document.JSONBytes{}
var _ document.Value = (*document.JSONReader)(nil)
var _ document.Embedded = document.LazyJSONValue{}
var _ document.Embedded = document.JSONBytes{}
var _ document.Embedded = (*document.JSONReader)(nil)

func ExampleLazyJSONValue() {
	type Input struct {
		InDoc document.Value    // Inline document
		EmDoc document.Embedded // Embedded document
	}

	myType := struct {
		Abc string
	}{
		Abc: "123",
	}

	params := &Input{
		InDoc: document.NewLazyValue(myType),
		EmDoc: document.NewLazyJSONValue(myType),
	}

	client.AnJSONAPIOperation(params)
}

func ExampleEmbedded_UnmarshalDocument() {
	type Output struct {
		InDoc document.Value
		EmDoc document.Embedded
	}

	v := Output{
		InDoc: document.NewLazyValue(map[string]string{"A": "cool document"}),
		EmDoc: document.JSONBytes([]byte(`{"A":"cool document"}`)),
	}

	var myType struct {
		A string
	}
	if err := v.EmDoc.UnmarshalDocument(&myType); err != nil {
		log.Fatalf("unable to unmarshal document, %v", err)
	}

	fmt.Printf("A: %v\n", myType.A)

	// Output:
	// A: cool document
}

type mockClient struct{}

func (mockClient) AnJSONAPIOperation(interface{}) {}

var client mockClient
