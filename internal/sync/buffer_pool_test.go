package sync

import (
	"bytes"
	"sync"
	"testing"
)

// exercises the pool the way protocols use it, with mixed sizes including
// oversized that will drop
func TestBufferPoolConcurrent(t *testing.T) {
	p := NewBufferPool()

	sizes := []int{16, 4096, 64 * 1024, maxBufferSize + 1}

	var wg sync.WaitGroup
	for g := 0; g < 16; g++ {
		wg.Add(1)
		go func(g int) {
			defer wg.Done()
			for i := 0; i < 200; i++ {
				size := sizes[(g+i)%len(sizes)]
				fill := byte('a' + g%26)
				want := bytes.Repeat([]byte{fill}, size)

				buf, err := p.Get(bytes.NewReader(want))
				if err != nil {
					t.Errorf("goroutine %d: %v", g, err)
					return
				}
				if !bytes.Equal(buf.Bytes(), want) {
					t.Errorf("goroutine %d iter %d: content mismatch for size %d", g, i, size)
					p.Put(buf)
					return
				}
				p.Put(buf)
			}
		}(g)
	}
	wg.Wait()
}
