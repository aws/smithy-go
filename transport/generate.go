//go:build codegen
// +build codegen

package transport

//go:generate go run -tags codegen ./internal/fieldset/codegen.go
//go:generate gofmt -w -s .
