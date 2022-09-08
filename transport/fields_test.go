package transport_test

import (
	"github.com/aws/smithy-go/transport"
	"github.com/google/go-cmp/cmp"
	"strings"
	"testing"
)

func TestFields(t *testing.T) {
	fields := &transport.Fields{}

	const testName = "SomeThing"
	if e, a := false, fields.Has(testName); e != a {
		t.Errorf("expect %v, got %v", e, a)
	}

	field := transport.NewField(testName, "foo")

	if old, ok := fields.Set(field); ok {
		t.Error("expect field to not be present")
	} else if !ok {
		// ensure we return a stub field with a valid name
		if e, a := testName, old.Name(); e != a {
			t.Errorf("expect %v, got %v", e, a)
		}
	}

	if e, a := true, fields.Has(testName); e != a {
		t.Errorf("expect %v, got %v", e, a)
	}

	oldField, ok := fields.Set(field.Add("bar"))
	if !ok {
		t.Error("expect field to be present")
	}
	compareValues(t, []string{"foo"}, oldField.Values())

	field = fields.Get(strings.ToLower(testName))
	compareValues(t, []string{"foo", "bar"}, field.Values())
	if e, a := testName, field.Name(); e != a {
		t.Errorf("expect %v, got %v", e, a)
	}

	newField := transport.NewField(strings.ToLower(testName), "new")
	_, ok = fields.Set(newField)
	if !ok {
		t.Errorf("expect field to be present")
	}

	field = fields.Get(testName)
	compareValues(t, []string{"new"}, field.Values())
	if e, a := testName, field.Name(); e != a {
		t.Errorf("expect %v, got %v", e, a)
	}

	field, ok = fields.Remove(testName)
	if !ok {
		t.Errorf("expect field to be present")
	}
	compareValues(t, []string{"new"}, field.Values())

	_, ok = fields.Set(newField)
	if ok {
		t.Errorf("expect field to not be present")
	}

	field = fields.Get(testName)
	if e, a := strings.ToLower(testName), field.Name(); e != a {
		t.Errorf("expect %v, got %v", e, a)
	}
}

func TestField(t *testing.T) {
	someThing := transport.NewField("someThing")

	if e, a := "someThing", someThing.Name(); e != a {
		t.Errorf("expect %v, got %v", e, a)
	}

	if e, a := false, someThing.HasValues(); e != a {
		t.Errorf("expect %v, got %v", e, a)
	}

	someThingV2 := someThing.Set("one")
	if e, a := true, someThingV2.HasValues(); e != a {
		t.Errorf("expect %v, got %v", e, a)
	}

	someThingV3 := someThingV2.Add("two")
	if e, a := true, someThingV3.HasValues(); e != a {
		t.Errorf("expect %v, got %v", e, a)
	}

	someThingV4 := someThingV3.Clear()

	someThingV5 := someThingV4.Set("four")

	compareValues(t, nil, someThing.Values())
	compareValues(t, []string{"one"}, someThingV2.Values())
	compareValues(t, []string{"one", "two"}, someThingV3.Values())
	compareValues(t, nil, someThingV4.Values())
	compareValues(t, []string{"four"}, someThingV5.Values())
}

func compareValues(t *testing.T, expect, actual []string) {
	t.Helper()
	if diff := cmp.Diff(expect, actual); len(diff) != 0 {
		t.Error(diff)
	}
}
