package xml

// writer interface used by the xml encoder to write an encoded xml
// document in a writer.
type writer interface {

	// Write takes in a byte slice and returns number of bytes written and error
	Write(p []byte) (n int, err error)

	// WriteRune takes in a rune and returns number of bytes written and error
	WriteRune(r rune) (n int, err error)

	// WriteString takes in a string and returns number of bytes written and error
	WriteString(s string) (n int, err error)

	// String method returns a string
	String() string

	// Bytes return a byte slice.
	Bytes() []byte
}

// Encoder is an XML encoder that supports construction of XML values
// using methods.
type Encoder struct {
	w       writer
	scratch *[]byte
}

// noOpFn prevents panics from unexpected defer statements
var noOpFn = func() {}

// NewEncoder returns an XML encoder
func NewEncoder(w writer) *Encoder {
	scratch := make([]byte, 64)

	return &Encoder{w: w, scratch: &scratch}
}

// String returns the string output of the XML encoder
func (e Encoder) String() string {
	return e.w.String()
}

// Bytes returns the []byte slice of the XML encoder
func (e Encoder) Bytes() []byte {
	return e.w.Bytes()
}

// RootElement builds a root element encoding
func (e Encoder) RootElement(key string, attr *[]Attr) Value {
	return newObject(e.w, e.scratch, nil).Key(key, attr)
}
