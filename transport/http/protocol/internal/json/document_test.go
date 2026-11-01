package json

import (
	"bytes"
	"encoding/json"
	"math"
	"reflect"
	"testing"

	"github.com/aws/smithy-go/document"
	documentjson "github.com/aws/smithy-go/document/json"
	"github.com/aws/smithy-go/prelude"
)

func readDocument(t *testing.T, input []byte) document.Opaque {
	t.Helper()
	d := NewShapeDeserializer(input)
	defer d.Close()
	var v document.Value
	if err := d.ReadDocument(prelude.Document, &v); err != nil {
		t.Fatal(err)
	}
	return v.(document.Opaque)
}

func TestReadDocumentNumbers(t *testing.T) {
	for _, literal := range []string{
		"0", "-0", "7", "9007199254740993", "-9007199254740993",
		"9223372036854775807", "-9223372036854775808", "18446744073709551615",
		"1.2500", "-0.0", "1.5e+10", "1E-9", "1e400", "1e-400",
	} {
		t.Run(literal, func(t *testing.T) {
			v := readDocument(t, []byte(literal))
			if got, ok := v.Value.(json.Number); !ok || got.String() != literal {
				t.Fatalf("document number = %#v (%T), want json.Number(%q)", v.Value, v.Value, literal)
			}

			var decoded any
			if err := documentjson.NewDecoder().DecodeJSONInterface(v.Value, &decoded); err != nil {
				t.Fatal(err)
			}
			if got, ok := decoded.(document.Number); !ok || got.String() != literal {
				t.Fatalf("decoded number = %#v (%T), want document.Number(%q)", decoded, decoded, literal)
			}

			s := NewShapeSerializer()
			defer s.Close()
			s.WriteDocument(prelude.Document, v)
			if got := string(s.Bytes()); got != literal {
				t.Errorf("round trip = %s, want %s", got, literal)
			}
		})
	}
}

func TestReadDocumentNestedValues(t *testing.T) {
	input := []byte(`{"map":{"large":9007199254740993,"negative":-9223372036854775808},"list":[9223372036854775807,1.2500,1E-9,null,true,false,"9007199254740993",[],{}]}`)
	v := readDocument(t, input)
	decoder := json.NewDecoder(bytes.NewReader(input))
	decoder.UseNumber()
	var want any
	if err := decoder.Decode(&want); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(v.Value, want) {
		t.Fatalf("document = %#v, want %#v", v.Value, want)
	}

	var decoded any
	if err := documentjson.NewDecoder().DecodeJSONInterface(v.Value, &decoded); err != nil {
		t.Fatal(err)
	}
	items := decoded.(map[string]any)["list"].([]any)
	if items[0] != document.Number("9223372036854775807") || items[1] != document.Number("1.2500") ||
		items[2] != document.Number("1E-9") || items[3] != nil || items[4] != true || items[5] != false ||
		items[6] != "9007199254740993" {
		t.Fatalf("decoded list = %#v", items)
	}

	s := NewShapeSerializer()
	defer s.Close()
	s.WriteDocument(prelude.Document, v)
	roundTrip := json.NewDecoder(bytes.NewReader(s.Bytes()))
	roundTrip.UseNumber()
	var got any
	if err := roundTrip.Decode(&got); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("round trip = %#v, want %#v", got, want)
	}
}

func TestReadDocumentInt64(t *testing.T) {
	for _, want := range []int64{7, 9007199254740993, -9007199254740993, math.MaxInt64, math.MinInt64} {
		// Use the standard JSON encoder to construct the exact wire integer.
		input, err := json.Marshal(want)
		if err != nil {
			t.Fatal(err)
		}
		t.Run(string(input), func(t *testing.T) {
			v := readDocument(t, input)
			var got int64
			if err := documentjson.NewDecoder().DecodeJSONInterface(v.Value, &got); err != nil {
				t.Fatal(err)
			}
			if got != want {
				t.Errorf("got %d, want %d", got, want)
			}
		})
	}
}

func TestReadDocumentNumericConversions(t *testing.T) {
	var unsigned uint64
	v := readDocument(t, []byte("18446744073709551615"))
	if err := documentjson.NewDecoder().DecodeJSONInterface(v.Value, &unsigned); err != nil || unsigned != math.MaxUint64 {
		t.Fatalf("uint64 = %d, err = %v", unsigned, err)
	}
	var floating float64
	v = readDocument(t, []byte("1.25e2"))
	if err := documentjson.NewDecoder().DecodeJSONInterface(v.Value, &floating); err != nil || floating != 125 {
		t.Fatalf("float64 = %v, err = %v", floating, err)
	}
	for _, literal := range []string{"9223372036854775808", "-9223372036854775809", "1.5", "1e2"} {
		t.Run(literal, func(t *testing.T) {
			v := readDocument(t, []byte(literal))
			var integer int64
			if err := documentjson.NewDecoder().DecodeJSONInterface(v.Value, &integer); err == nil {
				t.Errorf("expected int64 conversion of %s to fail, got %d", literal, integer)
			}
		})
	}
	v = readDocument(t, []byte("1e400"))
	if err := documentjson.NewDecoder().DecodeJSONInterface(v.Value, &floating); err == nil {
		t.Error("expected float64 overflow")
	}
}

func TestReadDocumentOwnsNumericText(t *testing.T) {
	input := []byte("9007199254740993")
	v := readDocument(t, input)
	input[0] = '1'
	if v.Value != json.Number("9007199254740993") {
		t.Errorf("number changed after input mutation: %#v", v.Value)
	}
}

func TestTypedNumbersRemainTyped(t *testing.T) {
	t.Run("int64", func(t *testing.T) {
		d := NewShapeDeserializer([]byte("9223372036854775807"))
		defer d.Close()
		var got int64
		if err := d.ReadInt64(prelude.Long, &got); err != nil || got != math.MaxInt64 {
			t.Fatalf("int64 = %d, err = %v", got, err)
		}
	})
	t.Run("float32", func(t *testing.T) {
		d := NewShapeDeserializer([]byte("1.25e2"))
		defer d.Close()
		var got float32
		if err := d.ReadFloat32(prelude.Float, &got); err != nil || got != 125 {
			t.Fatalf("float32 = %v, err = %v", got, err)
		}
	})
	t.Run("float64", func(t *testing.T) {
		d := NewShapeDeserializer([]byte("1.25e2"))
		defer d.Close()
		var got float64
		if err := d.ReadFloat64(prelude.Double, &got); err != nil || got != 125 {
			t.Fatalf("float64 = %v, err = %v", got, err)
		}
	})
}

func BenchmarkReadDocumentNumbers(b *testing.B) {
	input := []byte(`{"data":[7,9007199254740993,9223372036854775807,-9223372036854775808,1.25,1.5e+10],"meta":null}`)
	b.ReportAllocs()
	for b.Loop() {
		d := NewShapeDeserializer(input)
		var v document.Value
		err := d.ReadDocument(prelude.Document, &v)
		d.Close()
		if err != nil {
			b.Fatal(err)
		}
	}
}
