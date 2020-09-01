package middleware

import "fmt"

// RelativePosition provides specifying the relative position of a middleware
// in an ordered group.
type RelativePosition int

// Relative position for middleware in steps.
const (
	After RelativePosition = iota
	Before
)

type ider interface {
	ID() string
}

// orderedIDs provides an ordered collection of items with relative ordering
// by name.
type orderedIDs struct {
	order relativeOrder
	items map[string]ider

	unamedCounter int
}

func newOrderedIDs() *orderedIDs {
	return &orderedIDs{
		items: map[string]ider{},
	}
}

// Add injects the item to the relative position of the item group. Returns an
// error if the item already exists.
func (g *orderedIDs) Add(m ider, pos RelativePosition) error {
	if len(m.ID()) == 0 {
		return fmt.Errorf("empty ID, ID must not be empty")
	}

	if err := g.order.Add(m.ID(), pos); err != nil {
		return err
	}

	g.items[m.ID()] = m
	return nil
}

// Insert injects the item relative to an existing item id.  Return error if
// the original item does not exist, or the item being added already exists.
func (g *orderedIDs) Insert(m ider, relativeTo string, pos RelativePosition) error {
	if len(m.ID()) == 0 {
		return fmt.Errorf("insert ID must not be empty")
	}
	if len(relativeTo) == 0 {
		return fmt.Errorf("relative to ID must not be empty")
	}

	if err := g.order.Insert(m.ID(), relativeTo, pos); err != nil {
		return err
	}

	g.items[m.ID()] = m
	return nil
}

// Get returns the ider identified by id. If ider is not present, returns false
func (g *orderedIDs) Get(id string) (ider, bool) {
	v, ok := g.items[id]
	return v, ok
}

// Swap removes the item by id, replacing it with the new item. Returns error
// if the original item doesn't exist.
func (g *orderedIDs) Swap(id string, m ider) (ider, error) {
	if len(id) == 0 {
		return nil, fmt.Errorf("swap from ID must not be empty")
	}
	if len(m.ID()) == 0 {
		return nil, fmt.Errorf("swap to ID must not be empty")
	}

	if err := g.order.Swap(id, m.ID()); err != nil {
		return nil, err
	}

	removed := g.items[id]

	delete(g.items, id)
	g.items[m.ID()] = m

	return removed, nil
}

// Remove removes the item by id. Returns error if the item
// doesn't exist.
func (g *orderedIDs) Remove(id string) error {
	if len(id) == 0 {
		return fmt.Errorf("remove ID must not be empty")
	}

	if err := g.order.Remove(id); err != nil {
		return err
	}

	delete(g.items, id)
	return nil
}

func (g *orderedIDs) List() []string {
	items := g.order.List()
	order := make([]string, len(items))
	copy(order, items)

	return order
}

// Clear removes all entries.
func (g *orderedIDs) Clear() {
	g.order.Clear()
	g.items = map[string]ider{}
}

// GetOrder returns the item in the order it should be invoked in.
func (g *orderedIDs) GetOrder() []interface{} {
	order := g.order.List()
	ordered := make([]interface{}, len(order))
	for i := 0; i < len(order); i++ {
		ordered[i] = g.items[order[i]]
	}

	return ordered
}

// relativeOrder provides ordering of item
type relativeOrder struct {
	order []string
}

// Add inserts a item into the order relative to the position provided.
func (s *relativeOrder) Add(id string, pos RelativePosition) error {
	if _, ok := s.has(id); ok {
		return fmt.Errorf("already exists, %v", id)
	}

	switch pos {
	case Before:
		return s.insert(0, id, Before)

	case After:
		s.order = append(s.order, id)

	default:
		return fmt.Errorf("invalid position, %v", int(pos))
	}

	return nil
}

// Insert injects a item before or after the relative item. Returns
// an error if the relative item does not exist.
func (s *relativeOrder) Insert(id, relativeTo string, pos RelativePosition) error {
	if _, ok := s.has(id); ok {
		return fmt.Errorf("already exists, %v", id)
	}

	i, ok := s.has(relativeTo)
	if !ok {
		return fmt.Errorf("not found, %v", relativeTo)
	}

	return s.insert(i, id, pos)
}

// Swap will replace the item id with the to item. Returns an
// error if the original item id does not exist. Allows swapping out a
// item for another item with the same id.
func (s *relativeOrder) Swap(id, to string) error {
	i, ok := s.has(id)
	if !ok {
		return fmt.Errorf("not found, %v", id)
	}

	if _, ok = s.has(to); ok && id != to {
		return fmt.Errorf("already exists, %v", to)
	}

	s.order[i] = to
	return nil
}

func (s *relativeOrder) Remove(id string) error {
	i, ok := s.has(id)
	if !ok {
		return fmt.Errorf("not found, %v", id)
	}

	s.order = append(s.order[:i], s.order[i+1:]...)
	return nil
}

func (s *relativeOrder) List() []string {
	return s.order
}

func (s *relativeOrder) Clear() {
	s.order = s.order[0:0]
}

func (s *relativeOrder) insert(i int, id string, pos RelativePosition) error {
	switch pos {
	case Before:
		s.order = append(s.order, "")
		copy(s.order[i+1:], s.order[i:])
		s.order[i] = id

	case After:
		if i == len(s.order)-1 {
			s.order = append(s.order, id)
		} else {
			s.order = append(s.order[:i+1], append([]string{id}, s.order[i+1:]...)...)
		}

	default:
		return fmt.Errorf("invalid position, %v", int(pos))
	}

	return nil
}

func (s *relativeOrder) has(id string) (i int, found bool) {
	for i := 0; i < len(s.order); i++ {
		if s.order[i] == id {
			return i, true
		}
	}
	return 0, false
}
