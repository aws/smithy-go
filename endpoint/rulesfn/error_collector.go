package rulesfn

import "strings"

// ErrorCollector provides collection of errors that occurred while evaluating
// endpoint rule functions.
type ErrorCollector struct {
	errs []error
}

// NewErrorCollector returns an initialized [ErrorCollector] that errors can be
// added to.
func NewErrorCollector() *ErrorCollector {
	return &ErrorCollector{}
}

// AddError adds an error to the list of errors in the [ErrorCollector].
func (ec *ErrorCollector) AddError(err ...error) {
	ec.errs = append(ec.errs, err...)
}

// IsEmpty returns true if the [ErrorCollector] has errors. Returns false
// otherwise.
func (ec *ErrorCollector) IsEmpty() bool {
	return len(ec.errs) == 0
}

func (ec *ErrorCollector) Error() string {
	var out strings.Builder
	for i, err := range ec.errs {
		out.WriteString(err.Error())
		if i < len(ec.errs)-1 {
			out.WriteRune('\n')
		}
	}
	return "built in endpoint errors\n" + out.String()
}

// FnError provides a error wrapper for errors that occur while evaluating
// endpoint rule functions.
type FnError struct {
	Name string
	Err  error
}

func (e FnError) Unwrap() error { return e.Err }
func (e FnError) Error() string {
	return "endpoint helper " + e.Name + ", " + e.Err.Error()
}
