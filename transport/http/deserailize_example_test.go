package http

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"strconv"

	"github.com/awslabs/smithy-go/middleware"
)

func ExampleResponse_deserializeMiddleware() {
	stack := middleware.NewStack("deserialize example")

	type Output struct {
		FooName  string
		BarCount int
	}

	// Add a Deserialize middleware that will extract the RawResponse and
	// deserialize into the target output type.
	stack.Deserialize.Add(middleware.DeserializeMiddlewareFunc("example deserialize",
		func(ctx context.Context, in middleware.DeserializeInput, next middleware.DeserializeHandler) (
			out middleware.DeserializeOutput, err error,
		) {
			out, err = next.HandleDeserialize(ctx, in)
			if err != nil {
				return middleware.DeserializeOutput{}, err
			}

			rawResp := out.RawResponse.(*Response)
			out.Result = &Output{
				FooName: rawResp.Header.Get("foo-name"),
				BarCount: func() int {
					v, _ := strconv.Atoi(rawResp.Header.Get("bar-count"))
					return v
				}(),
			}

			return out, nil
		}),
		middleware.After,
	)

	// Mock example handler taking the request input and returning a response
	mockHandler := middleware.HandlerFunc(func(ctx context.Context, in interface{}) (
		output interface{}, err error,
	) {
		resp := &http.Response{
			StatusCode: 200,
			Header:     http.Header{},
		}
		resp.Header.Set("foo-name", "abc")
		resp.Header.Set("bar-count", "123")

		// The handler's returned response will be available as the
		// DeserializeOutput.RawResponse field.
		return &Response{
			Response: resp,
		}, nil
	})

	// Use the stack to decorate the handler then invoke the decorated handler
	// with the inputs.
	handler := middleware.DecorateHandler(mockHandler, stack)
	result, err := handler.Handle(context.Background(), struct{}{})
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to call operation, %v", err)
		return
	}

	// Cast the result returned by the handler to the expected Output type.
	res := result.(*Output)
	fmt.Println("FooName", res.FooName)
	fmt.Println("BarCount", res.BarCount)

	// Output:
	// FooName abc
	// BarCount 123
}
