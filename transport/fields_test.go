package transport

import (
	"strings"
	"testing"

	"github.com/google/go-cmp/cmp"
)

func TestFields(t *testing.T) {
	fields := newFields()

	const testName = "SomeThing"
	if e, a := false, fields.Has(testName); e != a {
		t.Errorf("expect %v, got %v", e, a)
	}

	field := NewField(testName, "foo")

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

	newField := NewField(strings.ToLower(testName), "new")
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

func TestFields_GetFields(t *testing.T) {
	fields := newFields()

	fields.Set(NewField("First", "1.a", "1.b"))
	fields.Set(NewField("Second", "2.a", "2.b"))
	fields.Set(NewField("Third", "3.a", "3.b"))

	fieldSlice := fields.GetFields()

	fields.Set(NewField("Fourth", "4.a", "4.b"))

	if e, a := 3, len(fieldSlice); e != a {
		t.Fatalf("expect %v fields, got %v", e, a)
	}

	fieldSlice[0] = fieldSlice[0].Add("1.c")
	compareValues(t, []string{"1.a", "1.b", "1.c"}, fieldSlice[0].Values())

	first := fields.Get("First")
	compareValues(t, []string{"1.a", "1.b"}, first.Values())
}

func TestField(t *testing.T) {
	someThing := NewField("someThing")

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
