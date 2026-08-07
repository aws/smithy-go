package serde

import (
	"strings"
	"testing"
	"unsafe"
)

func TestStringArena_Basic(t *testing.T) {
	var a StringArena
	a.Reset(MaxArenaSize)
	inputs := []string{"a", "", "hello", strings.Repeat("x", 100), "item", "amount"}
	got := make([]string, len(inputs))
	for i, in := range inputs {
		got[i] = a.String([]byte(in))
	}
	for i, in := range inputs {
		if got[i] != in {
			t.Errorf("[%d] got %q, want %q", i, got[i], in)
		}
	}
}

func TestStringArena_SourceMutation(t *testing.T) {
	var a StringArena
	a.Reset(MaxArenaSize)
	src := []byte("original")
	s := a.String(src)

	// the byte buffer this came from goes back into a pool and then is used
	// for another response
	copy(src, "MUTATED!")

	if s != "original" {
		t.Errorf("string changed with its source: got %q", s)
	}
}

func TestStringArena_Offsets(t *testing.T) {
	var a StringArena
	a.Reset(MaxArenaSize)
	first := a.String([]byte("first"))
	second := a.String([]byte("second"))

	if unsafe.StringData(first) == unsafe.StringData(second) {
		t.Fatal("distinct strings share a start pointer")
	}

	// 2 should be right after 1, in literal memory
	gap := uintptr(unsafe.Pointer(unsafe.StringData(second))) - uintptr(unsafe.Pointer(unsafe.StringData(first)))
	if gap != uintptr(len(first)) {
		t.Errorf("strings not contiguous in one block: gap %d, want %d", gap, len(first))
	}
}

func TestStringArena_Reset(t *testing.T) {
	var a StringArena
	a.Reset(MaxArenaSize)
	old := make([]string, 0, 64)
	for i := 0; i < 64; i++ {
		old = append(old, a.String([]byte(strings.Repeat("c", i%16+1))))
	}

	a.Reset(MaxArenaSize)
	for i := 0; i < 200; i++ {
		_ = a.String([]byte(strings.Repeat("z", i%16+1)))
	}

	// the zs shouldn't bleed into the cs
	for i, s := range old {
		if want := strings.Repeat("c", i%16+1); s != want {
			t.Fatalf("[%d] string corrupted across Reset: got %q, want %q", i, s, want)
		}
	}
}

func TestStringArena_MinArenaSize(t *testing.T) {
	var a StringArena
	a.Reset(MinArenaSize - 1) // should just not arena

	first := a.String([]byte("first"))
	second := a.String([]byte("second"))

	if first != "first" || second != "second" {
		t.Fatalf("got %q, %q", first, second)
	}
	if a.buf != nil {
		t.Errorf("arena allocated a block despite cap %d < MinArenaSize %d", MinArenaSize-1, MinArenaSize)
	}
}

func TestStringArena_MaxArenaSize(t *testing.T) {
	var a StringArena
	a.Reset(MaxArenaSize + 1) // should clamp

	huge := strings.Repeat("h", MaxArenaSize-1) // goes into arena
	if got := a.String([]byte(huge)); got != huge {
		t.Error("oversized string not returned intact")
	}
	hugest := strings.Repeat("hh", MaxArenaSize+1) // fallback
	if got := a.String([]byte(hugest)); got != hugest {
		t.Error("oversizeder string not returned intact")
	}
	if a.cap-len(a.buf) != 1 {
		t.Errorf("arena should've been maxed out minus one byte")
	}
}
