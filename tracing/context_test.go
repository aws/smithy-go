package tracing

import (
	"context"
	"sync"
	"testing"
)

// nopSpan has no fields so all values are equal, adding an int allows us to
// differentiate
type mockSpan struct {
	nopSpan
	id int
}

func TestSpanContextAPIs(t *testing.T) {
	parent := mockSpan{id: 1}
	child := mockSpan{id: 2}

	ctx := context.Background()
	span, ok := GetSpan(ctx)
	if ok {
		t.Error("should have no span at the start but it did")
	}

	// set & expect parent
	ctx = WithSpan(ctx, parent)
	span, _ = GetSpan(ctx)
	if actual, ok := span.(mockSpan); !ok || parent != actual {
		t.Errorf("span %d != %d", parent.id, actual.id)
	}

	// set & expect child
	ctx = WithSpan(ctx, child)
	span, _ = GetSpan(ctx)
	if actual, ok := span.(mockSpan); !ok || child != actual {
		t.Errorf("span %d != %d", child.id, actual.id)
	}

	// pop, expect popped child, with parent remaining
	ctx, span = PopSpan(ctx)
	if actual, ok := span.(mockSpan); !ok || child != actual {
		t.Errorf("span %d != %d", child.id, actual.id)
	}
	span, _ = GetSpan(ctx)
	if actual, ok := span.(mockSpan); !ok || parent != actual {
		t.Errorf("span %d != %d", parent.id, actual.id)
	}

	// pop, expect popped parent, with no span remaining
	ctx, span = PopSpan(ctx)
	if actual, ok := span.(mockSpan); !ok || parent != actual {
		t.Errorf("span %d != %d", parent.id, actual.id)
	}
	span, ok = GetSpan(ctx)
	if ok {
		t.Error("should have no span at the end but it did")
	}

	// pop, expect it to be a nop since nothing is left
	ctx, span = PopSpan(ctx)
	if _, ok := span.(nopSpan); !ok {
		t.Errorf("should have been nop span on last pop but was %T", span)
	}
}

func TestWithSpanSiblings(t *testing.T) {
	// three spans deep, the lineage's backing array has room for a fourth
	ctx := context.Background()
	for i := 1; i <= 3; i++ {
		ctx = WithSpan(ctx, mockSpan{id: i})
	}

	// two children of the same parent must not overwrite each other
	first := WithSpan(ctx, mockSpan{id: 4})
	second := WithSpan(ctx, mockSpan{id: 5})

	for _, c := range []struct {
		ctx  context.Context
		want int
	}{{first, 4}, {second, 5}, {ctx, 3}} {
		span, _ := GetSpan(c.ctx)
		if actual, ok := span.(mockSpan); !ok || actual.id != c.want {
			t.Errorf("span %d != %d", actual.id, c.want)
		}
	}
}

func TestWithSpanSiblingsConcurrent(t *testing.T) {
	ctx := context.Background()
	for i := 1; i <= 3; i++ {
		ctx = WithSpan(ctx, mockSpan{id: i})
	}

	// under -race, children started concurrently from one parent must not
	// write to memory the others read
	var wg sync.WaitGroup
	for i := 4; i < 12; i++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			span, _ := GetSpan(WithSpan(ctx, mockSpan{id: id}))
			if actual, ok := span.(mockSpan); !ok || actual.id != id {
				t.Errorf("span %d != %d", actual.id, id)
			}
		}(i)
	}
	wg.Wait()
}
