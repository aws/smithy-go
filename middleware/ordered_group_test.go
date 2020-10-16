package middleware

import (
	"reflect"
	"testing"
)

func TestOrderedIDsAdd(t *testing.T) {
	o := newOrderedIDs()

	noError(t, o.Add(mockIder("first"), After))
	noError(t, o.Add(mockIder("second"), After))
	noError(t, o.Add(mockIder("third"), After))
	noError(t, o.Add(mockIder("real-first"), Before))

	if err := o.Add(mockIder(""), After); err == nil {
		t.Errorf("expect error adding empty ID, got none")
	}
	if err := o.Add(mockIder("second"), After); err == nil {
		t.Errorf("expect error adding duplicate, got none")
	}
	if err := o.Add(mockIder("unique"), 123); err == nil {
		t.Errorf("expect error add unknown relative position, got none")
	}

	expectIDs := []string{"real-first", "first", "second", "third"}

	if e, a := expectIDs, o.List(); !reflect.DeepEqual(e, a) {
		t.Errorf("expect %v order, got %v", e, a)
	}
}

func TestOrderedIDsInsert(t *testing.T) {
	o := newOrderedIDs()

	noError(t, o.Add(mockIder("first"), After))
	noError(t, o.Insert(mockIder("third"), "first", After))
	noError(t, o.Insert(mockIder("second"), "third", Before))
	noError(t, o.Insert(mockIder("real-first"), "first", Before))
	noError(t, o.Insert(mockIder("not-yet-last"), "second", After))
	noError(t, o.Insert(mockIder("last"), "third", After))

	if err := o.Insert(mockIder(""), "first", After); err == nil {
		t.Errorf("expect error insert empty ID, got none")
	}
	if err := o.Insert(mockIder("second"), "", After); err == nil {
		t.Errorf("expect error insert with empty relative ID, got none")
	}
	if err := o.Insert(mockIder("second"), "third", After); err == nil {
		t.Errorf("expect error insert duplicate, got none")
	}
	if err := o.Insert(mockIder("unique"), "not-found", After); err == nil {
		t.Errorf("expect error insert not found relative ID, got none")
	}
	if err := o.Insert(mockIder("unique"), "first", 123); err == nil {
		t.Errorf("expect error insert unknown relative position, got none")
	}

	expectIDs := []string{"real-first", "first", "second", "not-yet-last", "third", "last"}
	if e, a := expectIDs, o.List(); !reflect.DeepEqual(e, a) {
		t.Errorf("expect %v order, got %v", e, a)
	}
}

func TestOrderedIDsGet(t *testing.T) {
	o := newOrderedIDs()

	noError(t, o.Add(mockIder("first"), After))
	noError(t, o.Add(mockIder("second"), After))

	f, ok := o.Get("not-found")
	if ok || f != nil {
		t.Fatalf("expect id not to be found, but was")
	}

	f, ok = o.Get("first")
	if !ok {
		t.Fatalf("expect id to be found, was not")
	}
	if e, a := "first", f.ID(); e != a {
		t.Errorf("expect %v id, got %v", e, a)
	}
}

func TestOrderedIDsSwap(t *testing.T) {
	o := newOrderedIDs()

	noError(t, o.Add(mockIder("first"), After))
	noError(t, o.Add(mockIder("second"), After))
	noError(t, o.Add(mockIder("third"), After))

	if _, err := o.Swap("first", mockIder("")); err == nil {
		t.Errorf("expect error swap empty ID, got none")
	}
	if _, err := o.Swap("", mockIder("second")); err == nil {
		t.Errorf("expect error swap with empty relative ID, got none")
	}

	if _, err := o.Swap("not-exists", mockIder("last")); err == nil {
		t.Errorf("expect error swap not-exists ID, got none")
	}
	if _, err := o.Swap("second", mockIder("first")); err == nil {
		t.Errorf("expect error swap to existing ID, got none")
	}

	r, err := o.Swap("second", mockIder("otherSecond"))
	noError(t, err)
	if r == nil {
		t.Fatalf("expect removed item to be returned")
	}
	if e, a := "second", r.ID(); e != a {
		t.Errorf("expect %v removed ider, got %v", e, a)
	}

	expectIDs := []string{"first", "otherSecond", "third"}
	if e, a := expectIDs, o.List(); !reflect.DeepEqual(e, a) {
		t.Errorf("expect %v order, got %v", e, a)
	}
}

func TestOrderedIDsRemove(t *testing.T) {
	o := newOrderedIDs()

	noError(t, o.Add(mockIder("first"), After))
	noError(t, o.Insert(mockIder("third"), "first", After))
	noError(t, o.Remove("first"))
	noError(t, o.Insert(mockIder("last"), "third", After))

	if err := o.Remove(""); err == nil {
		t.Errorf("expect error remove empty ID, got none")
	}
	if err := o.Remove("not-exists"); err == nil {
		t.Errorf("expect error remove not exists ID, got none")
	}

	expectIDs := []string{"third", "last"}
	if e, a := expectIDs, o.List(); !reflect.DeepEqual(e, a) {
		t.Errorf("expect %v order, got %v", e, a)
	}
}

func TestOrderedIDsClear(t *testing.T) {
	o := newOrderedIDs()

	noError(t, o.Add(mockIder("first"), After))
	noError(t, o.Add(mockIder("second"), After))

	o.Clear()

	noError(t, o.Add(mockIder("third"), After))
	noError(t, o.Add(mockIder("fourth"), After))

	expectIDs := []string{"third", "fourth"}
	if e, a := expectIDs, o.List(); !reflect.DeepEqual(e, a) {
		t.Errorf("expect %v order, got %v", e, a)
	}
}

func TestOrderedIDsGetOrder(t *testing.T) {
	o := newOrderedIDs()

	noError(t, o.Add(mockIder("first"), After))
	noError(t, o.Add(mockIder("second"), After))
	noError(t, o.Add(mockIder("third"), After))
	noError(t, o.Add(mockIder("real-first"), Before))

	expectIDs := []string{"real-first", "first", "second", "third"}
	if e, a := expectIDs, o.List(); !reflect.DeepEqual(e, a) {
		t.Errorf("expect %v order, got %v", e, a)
	}

	actualOrder := o.GetOrder()

	if e, a := len(expectIDs), len(actualOrder); e != a {
		t.Errorf("expect %v IDs, got %v", e, a)
	}

	for _, eID := range expectIDs {
		var found bool
		for _, aIder := range actualOrder {
			if e, a := eID, aIder.(ider).ID(); e == a {
				if found {
					t.Errorf("expect only one %v, got more", e)
				}
				found = true
			}
		}
		if !found {
			t.Errorf("expect to find %v, did not", eID)
		}
	}
}

func TestOrderedIDsSlots(t *testing.T) {
	o := newOrderedIDs()

	noError(t, o.AddSlot("first", After))
	noError(t, o.AddSlot("second", After))
	noError(t, o.InsertSlot("fourth", "second", After))
	noError(t, o.Insert(mockIder("third"), "second", After))

	expectIDs := []string{"first", "second", "third", "fourth"}
	if e, a := expectIDs, o.List(); !reflect.DeepEqual(e, a) {
		t.Errorf("expect %v order, got %v", e, a)
	}

	noError(t, o.Add(mockIder("second"), After))

	actualOrder := o.GetOrder()
	if e, a := 2, len(actualOrder); e != a {
		t.Errorf("expect %v IDs, got %v", e, a)
	}

	for _, eID := range []string{"second", "third"} {
		var found bool
		for _, aIder := range actualOrder {
			if e, a := eID, aIder.(ider).ID(); e == a {
				if found {
					t.Errorf("expect only one %v, got more", e)
				}
				found = true
			}
		}
		if !found {
			t.Errorf("expect to find %v, did not", eID)
		}
	}
}
