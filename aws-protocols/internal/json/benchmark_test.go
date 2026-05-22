package json

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"testing"

	"github.com/aws/smithy-go"
	"github.com/aws/smithy-go/prelude"
)

// Benchmark comparing old (stdlib json.Decoder + tree walk) vs new (fastjson
// ShapeDeserializer) for DynamoDB AttributeValue deserialization.
//
// The payload is a GetItem response with a realistic mix of attribute types.

// --- test payload ---

var benchPayload = []byte(`{
	"ConsumedCapacity": {
		"TableName": "Users",
		"CapacityUnits": 0.5
	},
	"Item": {
		"pk": {"S": "user#12345"},
		"sk": {"S": "profile"},
		"name": {"S": "Jane Doe"},
		"age": {"N": "34"},
		"verified": {"BOOL": true},
		"email": {"S": "jane@example.com"},
		"scores": {"NS": ["98", "76", "100", "88"]},
		"tags": {"SS": ["admin", "premium", "early-adopter"]},
		"avatar": {"B": "` + base64.StdEncoding.EncodeToString([]byte("fake-image-bytes-here")) + `"},
		"metadata": {"M": {
			"created": {"S": "2024-01-15T10:30:00Z"},
			"source": {"S": "web"},
			"loginCount": {"N": "142"},
			"preferences": {"M": {
				"theme": {"S": "dark"},
				"notifications": {"BOOL": true}
			}}
		}},
		"history": {"L": [
			{"M": {"action": {"S": "login"}, "ts": {"N": "1700000000"}}},
			{"M": {"action": {"S": "purchase"}, "ts": {"N": "1700100000"}}},
			{"M": {"action": {"S": "logout"}, "ts": {"N": "1700200000"}}}
		]},
		"nothing": {"NULL": true}
	}
}`)

// --- types (mirrors DynamoDB SDK types) ---

type AttributeValue interface{ isAttributeValue() }

type AttributeValueMemberS struct{ Value string }
type AttributeValueMemberN struct{ Value string }
type AttributeValueMemberB struct{ Value []byte }
type AttributeValueMemberBOOL struct{ Value bool }
type AttributeValueMemberNULL struct{ Value bool }
type AttributeValueMemberSS struct{ Value []string }
type AttributeValueMemberNS struct{ Value []string }
type AttributeValueMemberBS struct{ Value [][]byte }
type AttributeValueMemberL struct{ Value []AttributeValue }
type AttributeValueMemberM struct{ Value map[string]AttributeValue }

func (*AttributeValueMemberS) isAttributeValue()    {}
func (*AttributeValueMemberN) isAttributeValue()    {}
func (*AttributeValueMemberB) isAttributeValue()    {}
func (*AttributeValueMemberBOOL) isAttributeValue() {}
func (*AttributeValueMemberNULL) isAttributeValue() {}
func (*AttributeValueMemberSS) isAttributeValue()   {}
func (*AttributeValueMemberNS) isAttributeValue()   {}
func (*AttributeValueMemberBS) isAttributeValue()   {}
func (*AttributeValueMemberL) isAttributeValue()    {}
func (*AttributeValueMemberM) isAttributeValue()    {}

type ConsumedCapacity struct {
	TableName     *string
	CapacityUnits float64
}

type GetItemOutput struct {
	ConsumedCapacity *ConsumedCapacity
	Item             map[string]AttributeValue
}

// ==========================================================================
// OLD PATH: json.Decoder -> interface{} -> tree walk
// ==========================================================================

func oldDeserialize(data []byte) (*GetItemOutput, error) {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	var shape interface{}
	if err := decoder.Decode(&shape); err != nil && err != io.EOF {
		return nil, err
	}

	var out GetItemOutput
	if err := oldDeserializeGetItemOutput(&out, shape); err != nil {
		return nil, err
	}
	return &out, nil
}

func oldDeserializeGetItemOutput(v *GetItemOutput, value interface{}) error {
	if value == nil {
		return nil
	}

	shape, ok := value.(map[string]interface{})
	if !ok {
		return fmt.Errorf("unexpected JSON type %v", value)
	}

	for key, value := range shape {
		switch key {
		case "ConsumedCapacity":
			if err := oldDeserializeConsumedCapacity(&v.ConsumedCapacity, value); err != nil {
				return err
			}
		case "Item":
			if err := oldDeserializeAttributeMap(&v.Item, value); err != nil {
				return err
			}
		}
	}
	return nil
}

func oldDeserializeConsumedCapacity(v **ConsumedCapacity, value interface{}) error {
	if value == nil {
		return nil
	}

	shape, ok := value.(map[string]interface{})
	if !ok {
		return fmt.Errorf("unexpected JSON type %v", value)
	}

	sv := &ConsumedCapacity{}
	for key, value := range shape {
		switch key {
		case "TableName":
			if value != nil {
				jtv, ok := value.(string)
				if !ok {
					return fmt.Errorf("expected string, got %T", value)
				}
				sv.TableName = &jtv
			}
		case "CapacityUnits":
			if value != nil {
				jtv, ok := value.(json.Number)
				if !ok {
					return fmt.Errorf("expected number, got %T", value)
				}
				f, err := jtv.Float64()
				if err != nil {
					return err
				}
				sv.CapacityUnits = f
			}
		}
	}
	*v = sv
	return nil
}

func oldDeserializeAttributeMap(v *map[string]AttributeValue, value interface{}) error {
	if value == nil {
		return nil
	}

	shape, ok := value.(map[string]interface{})
	if !ok {
		return fmt.Errorf("unexpected JSON type %v", value)
	}

	mv := make(map[string]AttributeValue, len(shape))
	for key, value := range shape {
		var parsedVal AttributeValue
		if err := oldDeserializeAttributeValue(&parsedVal, value); err != nil {
			return err
		}
		mv[key] = parsedVal
	}
	*v = mv
	return nil
}

func oldDeserializeAttributeValue(v *AttributeValue, value interface{}) error {
	if value == nil {
		return nil
	}

	shape, ok := value.(map[string]interface{})
	if !ok {
		return fmt.Errorf("unexpected JSON type %v", value)
	}

	for key, value := range shape {
		if value == nil {
			continue
		}
		switch key {
		case "S":
			jtv, ok := value.(string)
			if !ok {
				return fmt.Errorf("expected string, got %T", value)
			}
			*v = &AttributeValueMemberS{Value: jtv}
			return nil
		case "N":
			jtv, ok := value.(string)
			if !ok {
				return fmt.Errorf("expected string, got %T", value)
			}
			*v = &AttributeValueMemberN{Value: jtv}
			return nil
		case "B":
			jtv, ok := value.(string)
			if !ok {
				return fmt.Errorf("expected string, got %T", value)
			}
			dv, err := base64.StdEncoding.DecodeString(jtv)
			if err != nil {
				return err
			}
			*v = &AttributeValueMemberB{Value: dv}
			return nil
		case "BOOL":
			jtv, ok := value.(bool)
			if !ok {
				return fmt.Errorf("expected bool, got %T", value)
			}
			*v = &AttributeValueMemberBOOL{Value: jtv}
			return nil
		case "NULL":
			jtv, ok := value.(bool)
			if !ok {
				return fmt.Errorf("expected bool, got %T", value)
			}
			*v = &AttributeValueMemberNULL{Value: jtv}
			return nil
		case "SS":
			if err := oldDeserializeStringSet(v, value); err != nil {
				return err
			}
			return nil
		case "NS":
			if err := oldDeserializeNumberSet(v, value); err != nil {
				return err
			}
			return nil
		case "BS":
			if err := oldDeserializeBinarySet(v, value); err != nil {
				return err
			}
			return nil
		case "L":
			if err := oldDeserializeList(v, value); err != nil {
				return err
			}
			return nil
		case "M":
			if err := oldDeserializeMap(v, value); err != nil {
				return err
			}
			return nil
		}
	}
	return nil
}

func oldDeserializeStringSet(v *AttributeValue, value interface{}) error {
	shape, ok := value.([]interface{})
	if !ok {
		return fmt.Errorf("expected list, got %T", value)
	}

	cv := make([]string, 0, len(shape))
	for _, value := range shape {
		jtv, ok := value.(string)
		if !ok {
			return fmt.Errorf("expected string, got %T", value)
		}
		cv = append(cv, jtv)
	}
	*v = &AttributeValueMemberSS{Value: cv}
	return nil
}

func oldDeserializeNumberSet(v *AttributeValue, value interface{}) error {
	shape, ok := value.([]interface{})
	if !ok {
		return fmt.Errorf("expected list, got %T", value)
	}

	cv := make([]string, 0, len(shape))
	for _, value := range shape {
		jtv, ok := value.(string)
		if !ok {
			return fmt.Errorf("expected string, got %T", value)
		}
		cv = append(cv, jtv)
	}
	*v = &AttributeValueMemberNS{Value: cv}
	return nil
}

func oldDeserializeBinarySet(v *AttributeValue, value interface{}) error {
	shape, ok := value.([]interface{})
	if !ok {
		return fmt.Errorf("expected list, got %T", value)
	}

	cv := make([][]byte, 0, len(shape))
	for _, value := range shape {
		jtv, ok := value.(string)
		if !ok {
			return fmt.Errorf("expected string, got %T", value)
		}
		dv, err := base64.StdEncoding.DecodeString(jtv)
		if err != nil {
			return err
		}
		cv = append(cv, dv)
	}
	*v = &AttributeValueMemberBS{Value: cv}
	return nil
}

func oldDeserializeList(v *AttributeValue, value interface{}) error {
	shape, ok := value.([]interface{})
	if !ok {
		return fmt.Errorf("expected list, got %T", value)
	}

	cv := make([]AttributeValue, 0, len(shape))
	for _, value := range shape {
		var col AttributeValue
		if err := oldDeserializeAttributeValue(&col, value); err != nil {
			return err
		}
		cv = append(cv, col)
	}
	*v = &AttributeValueMemberL{Value: cv}
	return nil
}

func oldDeserializeMap(v *AttributeValue, value interface{}) error {
	shape, ok := value.(map[string]interface{})
	if !ok {
		return fmt.Errorf("expected map, got %T", value)
	}

	mv := make(map[string]AttributeValue, len(shape))
	for key, value := range shape {
		var parsedVal AttributeValue
		if err := oldDeserializeAttributeValue(&parsedVal, value); err != nil {
			return err
		}
		mv[key] = parsedVal
	}
	*v = &AttributeValueMemberM{Value: mv}
	return nil
}

// ==========================================================================
// NEW PATH: fastjson ShapeDeserializer + schemas
// ==========================================================================

// --- schemas ---

var (
	schemaGetItemOutput = smithy.NewSchema(smithy.ShapeID{
		Namespace: "com.amazonaws.dynamodb", Name: "GetItemOutput",
	}, smithy.ShapeTypeStructure, 2)

	schemaConsumedCapacity = smithy.NewSchema(smithy.ShapeID{
		Namespace: "com.amazonaws.dynamodb", Name: "ConsumedCapacity",
	}, smithy.ShapeTypeStructure, 2)

	schemaAttributeValue = smithy.NewSchema(smithy.ShapeID{
		Namespace: "com.amazonaws.dynamodb", Name: "AttributeValue",
	}, smithy.ShapeTypeUnion, 10)

	schemaAttributeMap = smithy.NewSchema(smithy.ShapeID{
		Namespace: "com.amazonaws.dynamodb", Name: "AttributeMap",
	}, smithy.ShapeTypeMap, 0)

	schemaListAttributeValue = smithy.NewSchema(smithy.ShapeID{
		Namespace: "com.amazonaws.dynamodb", Name: "ListAttributeValue",
	}, smithy.ShapeTypeList, 0)

	schemaMapAttributeValue = smithy.NewSchema(smithy.ShapeID{
		Namespace: "com.amazonaws.dynamodb", Name: "MapAttributeValue",
	}, smithy.ShapeTypeMap, 0)

	schemaStringSetAttributeValue = smithy.NewSchema(smithy.ShapeID{
		Namespace: "com.amazonaws.dynamodb", Name: "StringSetAttributeValue",
	}, smithy.ShapeTypeList, 0)

	schemaNumberSetAttributeValue = smithy.NewSchema(smithy.ShapeID{
		Namespace: "com.amazonaws.dynamodb", Name: "NumberSetAttributeValue",
	}, smithy.ShapeTypeList, 0)

	schemaBinarySetAttributeValue = smithy.NewSchema(smithy.ShapeID{
		Namespace: "com.amazonaws.dynamodb", Name: "BinarySetAttributeValue",
	}, smithy.ShapeTypeList, 0)

	// member schemas (populated in init)
	schemaGetItemOutput_ConsumedCapacity *smithy.Schema
	schemaGetItemOutput_Item             *smithy.Schema
	schemaConsumedCapacity_TableName     *smithy.Schema
	schemaConsumedCapacity_CapacityUnits *smithy.Schema
	schemaAttributeValue_S               *smithy.Schema
	schemaAttributeValue_N               *smithy.Schema
	schemaAttributeValue_B               *smithy.Schema
	schemaAttributeValue_BOOL            *smithy.Schema
	schemaAttributeValue_NULL            *smithy.Schema
	schemaAttributeValue_SS              *smithy.Schema
	schemaAttributeValue_NS              *smithy.Schema
	schemaAttributeValue_BS              *smithy.Schema
	schemaAttributeValue_L               *smithy.Schema
	schemaAttributeValue_M               *smithy.Schema
)

func init() {
	schemaGetItemOutput_ConsumedCapacity = schemaGetItemOutput.AddMember("ConsumedCapacity", schemaConsumedCapacity)
	schemaGetItemOutput_Item = schemaGetItemOutput.AddMember("Item", schemaAttributeMap)

	schemaConsumedCapacity_TableName = schemaConsumedCapacity.AddMember("TableName", prelude.String)
	schemaConsumedCapacity_CapacityUnits = schemaConsumedCapacity.AddMember("CapacityUnits", prelude.Double)

	schemaAttributeValue_S = schemaAttributeValue.AddMember("S", prelude.String)
	schemaAttributeValue_N = schemaAttributeValue.AddMember("N", prelude.String)
	schemaAttributeValue_B = schemaAttributeValue.AddMember("B", prelude.Blob)
	schemaAttributeValue_BOOL = schemaAttributeValue.AddMember("BOOL", prelude.Boolean)
	schemaAttributeValue_NULL = schemaAttributeValue.AddMember("NULL", prelude.Boolean)
	schemaAttributeValue_SS = schemaAttributeValue.AddMember("SS", schemaStringSetAttributeValue)
	schemaAttributeValue_NS = schemaAttributeValue.AddMember("NS", schemaNumberSetAttributeValue)
	schemaAttributeValue_BS = schemaAttributeValue.AddMember("BS", schemaBinarySetAttributeValue)
	schemaAttributeValue_L = schemaAttributeValue.AddMember("L", schemaListAttributeValue)
	schemaAttributeValue_M = schemaAttributeValue.AddMember("M", schemaMapAttributeValue)

	schemaAttributeMap.AddMember("key", prelude.String)
	schemaAttributeMap.AddMember("value", schemaAttributeValue)

	schemaListAttributeValue.AddMember("member", schemaAttributeValue)

	schemaMapAttributeValue.AddMember("key", prelude.String)
	schemaMapAttributeValue.AddMember("value", schemaAttributeValue)

	schemaStringSetAttributeValue.AddMember("member", prelude.String)
	schemaNumberSetAttributeValue.AddMember("member", prelude.String)
	schemaBinarySetAttributeValue.AddMember("member", prelude.Blob)
}

// --- new deserializers ---

func newDeserialize(data []byte) (*GetItemOutput, error) {
	d := NewShapeDeserializer(data)
	defer d.Close()
	var out GetItemOutput
	if err := newDeserializeGetItemOutput(d, &out); err != nil {
		return nil, err
	}
	return &out, nil
}

func newDeserializeGetItemOutput(d smithy.ShapeDeserializer, v *GetItemOutput) error {
	return smithy.ReadStruct(d, schemaGetItemOutput, func(ms *smithy.Schema) error {
		switch ms {
		case schemaGetItemOutput_ConsumedCapacity:
			v.ConsumedCapacity = &ConsumedCapacity{}
			return newDeserializeConsumedCapacity(d, v.ConsumedCapacity)
		case schemaGetItemOutput_Item:
			return newDeserializeAttributeMap(d, &v.Item)
		}
		return nil
	})
}

func newDeserializeConsumedCapacity(d smithy.ShapeDeserializer, v *ConsumedCapacity) error {
	return smithy.ReadStruct(d, schemaConsumedCapacity, func(ms *smithy.Schema) error {
		switch ms {
		case schemaConsumedCapacity_TableName:
			v.TableName = new(string)
			return d.ReadString(schemaConsumedCapacity_TableName, v.TableName)
		case schemaConsumedCapacity_CapacityUnits:
			return d.ReadFloat64(schemaConsumedCapacity_CapacityUnits, &v.CapacityUnits)
		}
		return nil
	})
}

func newDeserializeAttributeMap(d smithy.ShapeDeserializer, v *map[string]AttributeValue) error {
	return smithy.ReadMap(d, schemaAttributeMap, func(key string) error {
		var av AttributeValue
		if err := newDeserializeAttributeValue(d, &av); err != nil {
			return err
		}
		if *v == nil {
			*v = map[string]AttributeValue{}
		}
		(*v)[key] = av
		return nil
	})
}

func newDeserializeAttributeValue(d smithy.ShapeDeserializer, v *AttributeValue) error {
	return smithy.ReadUnion(d, schemaAttributeValue, func(ms *smithy.Schema) error {
		switch ms {
		case schemaAttributeValue_S:
			vv := &AttributeValueMemberS{}
			*v = vv
			return d.ReadString(schemaAttributeValue_S, &vv.Value)
		case schemaAttributeValue_N:
			vv := &AttributeValueMemberN{}
			*v = vv
			return d.ReadString(schemaAttributeValue_N, &vv.Value)
		case schemaAttributeValue_B:
			vv := &AttributeValueMemberB{}
			*v = vv
			return d.ReadBlob(schemaAttributeValue_B, &vv.Value)
		case schemaAttributeValue_BOOL:
			vv := &AttributeValueMemberBOOL{}
			*v = vv
			return d.ReadBool(schemaAttributeValue_BOOL, &vv.Value)
		case schemaAttributeValue_NULL:
			vv := &AttributeValueMemberNULL{}
			*v = vv
			return d.ReadBool(schemaAttributeValue_NULL, &vv.Value)
		case schemaAttributeValue_SS:
			vv := &AttributeValueMemberSS{}
			*v = vv
			return smithy.ReadList(d, schemaStringSetAttributeValue, func() error {
				var s string
				if err := d.ReadString(nil, &s); err != nil {
					return err
				}
				vv.Value = append(vv.Value, s)
				return nil
			})
		case schemaAttributeValue_NS:
			vv := &AttributeValueMemberNS{}
			*v = vv
			return smithy.ReadList(d, schemaNumberSetAttributeValue, func() error {
				var s string
				if err := d.ReadString(nil, &s); err != nil {
					return err
				}
				vv.Value = append(vv.Value, s)
				return nil
			})
		case schemaAttributeValue_BS:
			vv := &AttributeValueMemberBS{}
			*v = vv
			return smithy.ReadList(d, schemaBinarySetAttributeValue, func() error {
				var b []byte
				if err := d.ReadBlob(nil, &b); err != nil {
					return err
				}
				vv.Value = append(vv.Value, b)
				return nil
			})
		case schemaAttributeValue_L:
			vv := &AttributeValueMemberL{}
			*v = vv
			return smithy.ReadList(d, schemaListAttributeValue, func() error {
				var av AttributeValue
				if err := newDeserializeAttributeValue(d, &av); err != nil {
					return err
				}
				vv.Value = append(vv.Value, av)
				return nil
			})
		case schemaAttributeValue_M:
			vv := &AttributeValueMemberM{}
			*v = vv
			return smithy.ReadMap(d, schemaMapAttributeValue, func(key string) error {
				var av AttributeValue
				if err := newDeserializeAttributeValue(d, &av); err != nil {
					return err
				}
				if vv.Value == nil {
					vv.Value = map[string]AttributeValue{}
				}
				vv.Value[key] = av
				return nil
			})
		}
		return nil
	})
}

// ==========================================================================
// Benchmarks
// ==========================================================================

func BenchmarkDeserialize_Old(b *testing.B) {
	b.ReportAllocs()
	for b.Loop() {
		out, err := oldDeserialize(benchPayload)
		if err != nil {
			b.Fatal(err)
		}
		if out.Item == nil {
			b.Fatal("nil item")
		}
	}
}

func BenchmarkDeserialize_New(b *testing.B) {
	b.ReportAllocs()
	for b.Loop() {
		out, err := newDeserialize(benchPayload)
		if err != nil {
			b.Fatal(err)
		}
		if out.Item == nil {
			b.Fatal("nil item")
		}
	}
}

// ==========================================================================
// Serialize benchmarks
// ==========================================================================

func newSerialize(out *GetItemOutput) ([]byte, error) {
	s := NewShapeSerializer()
	newSerializeGetItemOutput(s, out)
	return s.Bytes(), nil
}

func newSerializeGetItemOutput(s smithy.ShapeSerializer, v *GetItemOutput) {
	s.WriteStruct(schemaGetItemOutput)
	if v.ConsumedCapacity != nil {
		newSerializeConsumedCapacity(s, schemaGetItemOutput_ConsumedCapacity, v.ConsumedCapacity)
	}
	if v.Item != nil {
		newSerializeAttributeMap(s, schemaGetItemOutput_Item, v.Item)
	}
	s.CloseStruct()
}

func newSerializeConsumedCapacity(s smithy.ShapeSerializer, schema *smithy.Schema, v *ConsumedCapacity) {
	s.WriteStruct(schema)
	if v.TableName != nil {
		s.WriteString(schemaConsumedCapacity_TableName, *v.TableName)
	}
	s.WriteFloat64(schemaConsumedCapacity_CapacityUnits, v.CapacityUnits)
	s.CloseStruct()
}

func newSerializeAttributeMap(s smithy.ShapeSerializer, schema *smithy.Schema, m map[string]AttributeValue) {
	s.WriteMap(schema)
	for k, v := range m {
		s.WriteKey(nil, k)
		newSerializeAttributeValue(s, v)
	}
	s.CloseMap()
}

func newSerializeAttributeValue(s smithy.ShapeSerializer, v AttributeValue) {
	switch av := v.(type) {
	case *AttributeValueMemberS:
		s.WriteUnion(schemaAttributeValue, schemaAttributeValue_S, serializerFunc(func(s smithy.ShapeSerializer) {
			s.WriteString(schemaAttributeValue_S, av.Value)
		}))
	case *AttributeValueMemberN:
		s.WriteUnion(schemaAttributeValue, schemaAttributeValue_N, serializerFunc(func(s smithy.ShapeSerializer) {
			s.WriteString(schemaAttributeValue_N, av.Value)
		}))
	case *AttributeValueMemberB:
		s.WriteUnion(schemaAttributeValue, schemaAttributeValue_B, serializerFunc(func(s smithy.ShapeSerializer) {
			s.WriteBlob(schemaAttributeValue_B, av.Value)
		}))
	case *AttributeValueMemberBOOL:
		s.WriteUnion(schemaAttributeValue, schemaAttributeValue_BOOL, serializerFunc(func(s smithy.ShapeSerializer) {
			s.WriteBool(schemaAttributeValue_BOOL, av.Value)
		}))
	case *AttributeValueMemberNULL:
		s.WriteUnion(schemaAttributeValue, schemaAttributeValue_NULL, serializerFunc(func(s smithy.ShapeSerializer) {
			s.WriteBool(schemaAttributeValue_NULL, av.Value)
		}))
	case *AttributeValueMemberSS:
		s.WriteUnion(schemaAttributeValue, schemaAttributeValue_SS, serializerFunc(func(s smithy.ShapeSerializer) {
			s.WriteList(schemaAttributeValue_SS)
			for _, item := range av.Value {
				s.WriteString(nil, item)
			}
			s.CloseList()
		}))
	case *AttributeValueMemberNS:
		s.WriteUnion(schemaAttributeValue, schemaAttributeValue_NS, serializerFunc(func(s smithy.ShapeSerializer) {
			s.WriteList(schemaAttributeValue_NS)
			for _, item := range av.Value {
				s.WriteString(nil, item)
			}
			s.CloseList()
		}))
	case *AttributeValueMemberBS:
		s.WriteUnion(schemaAttributeValue, schemaAttributeValue_BS, serializerFunc(func(s smithy.ShapeSerializer) {
			s.WriteList(schemaAttributeValue_BS)
			for _, item := range av.Value {
				s.WriteBlob(nil, item)
			}
			s.CloseList()
		}))
	case *AttributeValueMemberL:
		s.WriteUnion(schemaAttributeValue, schemaAttributeValue_L, serializerFunc(func(s smithy.ShapeSerializer) {
			s.WriteList(schemaAttributeValue_L)
			for _, item := range av.Value {
				newSerializeAttributeValue(s, item)
			}
			s.CloseList()
		}))
	case *AttributeValueMemberM:
		s.WriteUnion(schemaAttributeValue, schemaAttributeValue_M, serializerFunc(func(s smithy.ShapeSerializer) {
			s.WriteMap(schemaAttributeValue_M)
			for k, item := range av.Value {
				s.WriteKey(nil, k)
				newSerializeAttributeValue(s, item)
			}
			s.CloseMap()
		}))
	}
}

type serializerFunc func(smithy.ShapeSerializer)

func (f serializerFunc) Serialize(s smithy.ShapeSerializer) { f(s) }

var benchOutput *GetItemOutput

func init() {
	var err error
	benchOutput, err = newDeserialize(benchPayload)
	if err != nil {
		panic(err)
	}
}

func BenchmarkSerialize_New(b *testing.B) {
	b.ReportAllocs()
	for b.Loop() {
		out, err := newSerialize(benchOutput)
		if err != nil {
			b.Fatal(err)
		}
		if len(out) == 0 {
			b.Fatal("empty")
		}
	}
}

// ==========================================================================
// Large payload
// ==========================================================================

func generateLargePayload(targetSize int) []byte {
	var buf bytes.Buffer
	buf.WriteString(`{"ConsumedCapacity":{"TableName":"Bench","CapacityUnits":1.0},"Item":{`)
	i := 0
	for buf.Len() < targetSize {
		if i > 0 {
			buf.WriteByte(',')
		}
		key := fmt.Sprintf("attr_%d", i)
		switch i % 7 {
		case 0:
			fmt.Fprintf(&buf, `"%s":{"S":"value-%d-padding-to-add-some-length-here"}`, key, i)
		case 1:
			fmt.Fprintf(&buf, `"%s":{"N":"%d"}`, key, i*100)
		case 2:
			fmt.Fprintf(&buf, `"%s":{"BOOL":%t}`, key, i%2 == 0)
		case 3:
			fmt.Fprintf(&buf, `"%s":{"SS":["alpha%d","bravo%d","charlie%d","delta%d"]}`, key, i, i, i, i)
		case 4:
			fmt.Fprintf(&buf, `"%s":{"NS":["%d","%d","%d","%d"]}`, key, i, i+1, i+2, i+3)
		case 5:
			fmt.Fprintf(&buf, `"%s":{"M":{"nested":{"S":"v%d"},"count":{"N":"%d"},"flag":{"BOOL":true}}}`, key, i, i)
		case 6:
			fmt.Fprintf(&buf, `"%s":{"L":[{"S":"item%d"},{"N":"%d"},{"BOOL":true},{"NULL":true}]}`, key, i, i)
		}
		i++
	}
	buf.WriteString(`}}`)
	return buf.Bytes()
}

var largePayload = generateLargePayload(512 * 1024 * 1024)

func BenchmarkLargePayload_Old(b *testing.B) {
	b.SetBytes(int64(len(largePayload)))
	b.ReportAllocs()
	for b.Loop() {
		if _, err := oldDeserialize(largePayload); err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkLargePayload_New(b *testing.B) {
	b.SetBytes(int64(len(largePayload)))
	b.ReportAllocs()
	for b.Loop() {
		if _, err := newDeserialize(largePayload); err != nil {
			b.Fatal(err)
		}
	}
}

