package json

import (
	"fmt"
	"reflect"
	"strings"
	"testing"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/prelude"
	"github.com/aws/smithy-go/traits"
)

var (
	orderSchema = smithy.NewSchema(smithy.ShapeID{Namespace: "com.test", Name: "Order"}, smithy.ShapeTypeStructure, 7)
	_           = orderSchema.AddMember("Alpha", prelude.String)
	_           = orderSchema.AddMember("Beta", prelude.Integer)
	_           = orderSchema.AddMember("Gamma", prelude.Boolean)
	_           = orderSchema.AddMember("Delta", prelude.String, &traits.JSONName{Name: "delta_json"})
	_           = orderSchema.AddMember("Epsilon", prelude.String)
	_           = orderSchema.AddMember("Alp", prelude.String)
	_           = orderSchema.AddMember("Zeta", prelude.String)

	orderErrSchema = smithy.NewSchema(smithy.ShapeID{Namespace: "com.test", Name: "OrderErr"}, smithy.ShapeTypeStructure, 2,
		&traits.Error{Type: "client"})
	_ = orderErrSchema.AddMember("Code", prelude.String)
	_ = orderErrSchema.AddMember("Message", prelude.String)
)

func readValue(d *ShapeDeserializer, m *smithy.Schema) (string, error) {
	switch m.Type() {
	case smithy.ShapeTypeInteger:
		var v int32
		err := d.ReadInt32(m, &v)
		return fmt.Sprint(v), err
	case smithy.ShapeTypeBoolean:
		var v bool
		err := d.ReadBool(m, &v)
		return fmt.Sprint(v), err
	default:
		var v string
		err := d.ReadString(m, &v)
		return v, err
	}
}

// readOrdered returns "Member=value" for each member decoded, in the order
// they were encountered in the payload.
func readOrdered(payload string, schema *smithy.Schema, direct bool, opts ...func(*Options)) ([]string, error) {
	d := NewShapeDeserializer([]byte(payload), opts...)
	defer d.Close()

	var got []string
	fn := func(m *smithy.Schema) error {
		v, err := readValue(d, m)
		if err != nil {
			return err
		}
		got = append(got, m.MemberName()+"="+v)
		return nil
	}

	var err error
	if direct {
		err = d.DirectReadStruct(schema, fn)
	} else {
		err = smithy.ReadStruct(d, schema, fn)
	}
	return got, err
}

func useJSONName(o *Options) { o.UseJSONName = true }

func TestReadStructMemberOrder(t *testing.T) {
	cases := map[string]struct {
		payload string
		schema  *smithy.Schema
		opts    []func(*Options)
		want    []string
	}{
		"empty": {
			payload: `{}`,
		},
		"in order": {
			payload: `{"Alpha":"a","Beta":1,"Gamma":true,"Delta":"d","Epsilon":"e"}`,
			want:    []string{"Alpha=a", "Beta=1", "Gamma=true", "Delta=d", "Epsilon=e"},
		},
		"reversed": {
			payload: `{"Epsilon":"e","Delta":"d","Gamma":true,"Beta":1,"Alpha":"a"}`,
			want:    []string{"Epsilon=e", "Delta=d", "Gamma=true", "Beta=1", "Alpha=a"},
		},
		"missing members": {
			payload: `{"Alpha":"a","Gamma":true,"Epsilon":"e"}`,
			want:    []string{"Alpha=a", "Gamma=true", "Epsilon=e"},
		},
		"unknown members interleaved": {
			payload: `{"Alpha":"a","Unknown":{"x":[1,2,{"y":null}]},"Beta":1,"Nope":"z","Gamma":true}`,
			want:    []string{"Alpha=a", "Beta=1", "Gamma=true"},
		},
		"null values skipped": {
			payload: `{"Alpha":null,"Beta":1,"Gamma":null,"Delta":"d"}`,
			want:    []string{"Beta=1", "Delta=d"},
		},
		"name is a prefix of the next expected member": {
			payload: `{"Alp":"p","Alpha":"a","Beta":1}`,
			want:    []string{"Alp=p", "Alpha=a", "Beta=1"},
		},
		"expected member is a prefix of the name": {
			payload: `{"Alpha":"a","Betas":1,"Beta":2}`,
			want:    []string{"Alpha=a", "Beta=2"},
		},
		"same length different name": {
			payload: `{"Alpha":"a","Beto":1,"Beta":2}`,
			want:    []string{"Alpha=a", "Beta=2"},
		},
		"escaped key": {
			payload: `{"Alp\u0068a":"a","Beta":1}`,
			want:    []string{"Alpha=a", "Beta=1"},
		},
		"duplicate key": {
			payload: `{"Alpha":"a","Alpha":"b","Beta":1}`,
			want:    []string{"Alpha=a", "Alpha=b", "Beta=1"},
		},
		"jump back and forward": {
			payload: `{"Beta":1,"Alpha":"a","Beta":2,"Gamma":false}`,
			want:    []string{"Beta=1", "Alpha=a", "Beta=2", "Gamma=false"},
		},
		"jsonName ignored by default": {
			payload: `{"Alpha":"a","delta_json":"d","Epsilon":"e"}`,
			want:    []string{"Alpha=a", "Epsilon=e"},
		},
		"jsonName used": {
			payload: `{"Alpha":"a","delta_json":"d","Epsilon":"e"}`,
			opts:    []func(*Options){useJSONName},
			want:    []string{"Alpha=a", "Delta=d", "Epsilon=e"},
		},
		"jsonName used then in order": {
			payload: `{"delta_json":"d","Epsilon":"e","Alp":"p","Zeta":"z"}`,
			opts:    []func(*Options){useJSONName},
			want:    []string{"Delta=d", "Epsilon=e", "Alp=p", "Zeta=z"},
		},
		"member name still matches with jsonName enabled": {
			payload: `{"Delta":"d","Epsilon":"e"}`,
			opts:    []func(*Options){useJSONName},
			want:    []string{"Delta=d", "Epsilon=e"},
		},
		"error message alias": {
			payload: `{"Code":"c","message":"m"}`,
			schema:  orderErrSchema,
			want:    []string{"Code=c", "Message=m"},
		},
		"error message alias then exact": {
			payload: `{"message":"m","Code":"c","Message":"n"}`,
			schema:  orderErrSchema,
			want:    []string{"Message=m", "Code=c", "Message=n"},
		},
	}

	for name, c := range cases {
		schema := c.schema
		if schema == nil {
			schema = orderSchema
		}

		for _, direct := range []bool{false, true} {
			t.Run(fmt.Sprintf("%s/direct=%v", name, direct), func(t *testing.T) {
				got, err := readOrdered(c.payload, schema, direct, c.opts...)
				if err != nil {
					t.Fatalf("unexpected error: %v", err)
				}
				if !reflect.DeepEqual(got, c.want) {
					t.Errorf("expect %v, got %v", c.want, got)
				}
			})
		}
	}
}

const wideN = 30

var (
	wideSchema  = smithy.NewSchema(smithy.ShapeID{Namespace: "com.test", Name: "Wide"}, smithy.ShapeTypeStructure, wideN)
	wideMembers [wideN]*smithy.Schema
)

func init() {
	targets := []*smithy.Schema{prelude.String, prelude.Integer, prelude.Boolean}
	for i := range wideMembers {
		wideMembers[i] = wideSchema.AddMember(fmt.Sprintf("SomeFieldName%02d", i), targets[i%len(targets)])
	}
}

func widePayload(indexes []int) []byte {
	var sb strings.Builder
	sb.WriteByte('{')
	for n, i := range indexes {
		if n > 0 {
			sb.WriteByte(',')
		}
		fmt.Fprintf(&sb, `"%s":`, wideMembers[i].MemberName())
		switch wideMembers[i].Type() {
		case smithy.ShapeTypeInteger:
			fmt.Fprintf(&sb, `%d`, i*7)
		case smithy.ShapeTypeBoolean:
			sb.WriteString(`true`)
		default:
			fmt.Fprintf(&sb, `"value-%d"`, i)
		}
	}
	sb.WriteByte('}')
	return []byte(sb.String())
}

func wideIndexes(keep func(i int) bool, reverse bool) []int {
	var out []int
	for i := 0; i < wideN; i++ {
		if keep(i) {
			out = append(out, i)
		}
	}
	if reverse {
		for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
			out[i], out[j] = out[j], out[i]
		}
	}
	return out
}

func BenchmarkReadStruct(b *testing.B) {
	payloads := map[string][]byte{
		"in-order":      widePayload(wideIndexes(func(int) bool { return true }, false)),
		"reversed":      widePayload(wideIndexes(func(int) bool { return true }, true)),
		"sparse":        widePayload(wideIndexes(func(i int) bool { return i%3 == 0 }, false)),
		"in-order-half": widePayload(wideIndexes(func(i int) bool { return i < wideN/2 }, false)),
	}

	for _, name := range []string{"in-order", "reversed", "sparse", "in-order-half"} {
		payload := payloads[name]

		for _, direct := range []bool{false, true} {
			path := "interface"
			if direct {
				path = "direct"
			}

			b.Run(name+"/"+path, func(b *testing.B) {
				b.ReportAllocs()
				b.SetBytes(int64(len(payload)))

				var sink int
				for b.Loop() {
					d := NewShapeDeserializer(payload)
					fn := func(m *smithy.Schema) error {
						switch m.Type() {
						case smithy.ShapeTypeInteger:
							var v int32
							if err := d.ReadInt32(m, &v); err != nil {
								return err
							}
							sink += int(v)
						case smithy.ShapeTypeBoolean:
							var v bool
							if err := d.ReadBool(m, &v); err != nil {
								return err
							}
						default:
							var v string
							if err := d.ReadString(m, &v); err != nil {
								return err
							}
							sink += len(v)
						}
						return nil
					}

					var err error
					if direct {
						err = d.DirectReadStruct(wideSchema, fn)
					} else {
						err = smithy.ReadStruct(d, wideSchema, fn)
					}
					if err != nil {
						b.Fatal(err)
					}
					d.Close()
				}
				_ = sink
			})
		}
	}
}
