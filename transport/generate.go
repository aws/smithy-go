//go:build codegen
// +build codegen

package transport

//go:generate go run -tags codegen ./internal/fieldset/main.go
//go:generate gofmt -w -s .
