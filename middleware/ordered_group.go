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

type namer interface {
	Name() string
}

// orderedGroup provides an ordered collection of items with relative ordering
// by name.
type orderedGroup struct {
	order relativeOrder
	items map[string]interface{}

	unamedCounter int
}

func (g *orderedGroup) generateName() string {
	g.unamedCounter++
	return fmt.Sprintf("unnamed %d", g.unamedCounter)
}

// Add injects the item to the relative position of the item group.
// Returns an error if the item already exists.
func (g *orderedGroup) Add(m interface{}, pos RelativePosition) error {
	var mName string
	if v, ok := m.(namer); ok && len(v.Name()) != 0 {
		mName = v.Name()
	} else {
		mName = g.generateName()
	}

	if err := g.order.Add(mName, pos); err != nil {
		return err
	}

	g.items[mName] = m
	return nil
}

// Insert injects the item relative to an existing item name.
// Return error if the original item does not exist, or the item
// being added already exists.
func (g *orderedGroup) Insert(m interface{}, relativeTo string, pos RelativePosition) error {
	var mName string
	if v, ok := m.(namer); ok && len(v.Name()) != 0 {
		mName = v.Name()
	} else {
		mName = g.generateName()
	}

	if err := g.order.Insert(mName, relativeTo, pos); err != nil {
		return err
	}

	g.items[mName] = m
	return nil
}

// Swap removes the item by name, replacing it with the new item.
// Returns error if the original item doesn't exist.
func (g *orderedGroup) Swap(name string, m interface{}) error {
	var mName string
	if v, ok := m.(namer); ok && len(v.Name()) != 0 {
		mName = v.Name()
	} else {
		mName = g.generateName()
	}

	if err := g.order.Swap(name, mName); err != nil {
		return err
	}

	delete(g.items, name)
	g.items[mName] = m
	return nil
}

// Remove removes the item by name. Returns error if the item
// doesn't exist.
func (g *orderedGroup) Remove(name string) error {
	if err := g.order.Remove(name); err != nil {
		return err
	}

	delete(g.items, name)
	return nil
}

// GetOrder returns the item in the order it should be invoked in.
func (g *orderedGroup) GetOrder() []interface{} {
	order := g.order.GetOrder()
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
func (s *relativeOrder) Add(name string, pos RelativePosition) error {
	if _, ok := s.has(name); ok {
		return fmt.Errorf("already exists, %v", name)
	}

	switch pos {
	case Before:
		return s.insert(0, name, Before)

	case After:
		s.order = append(s.order, name)

	default:
		return fmt.Errorf("invalid position, %v", int(pos))
	}

	return nil
}

// Insert injects a item before or after the relative item. Returns
// an error if the relative item does not exist.
func (s *relativeOrder) Insert(name, relativeTo string, pos RelativePosition) error {
	if _, ok := s.has(name); ok {
		return fmt.Errorf("already exists, %v", name)
	}

	i, ok := s.has(relativeTo)
	if !ok {
		return fmt.Errorf("not found, %v", relativeTo)
	}

	return s.insert(i, name, pos)
}

// Swap will replace the item name with the to item. Returns an
// error if the original item name does not exist. Allows swapping out a
// item for another item with the same name.
func (s relativeOrder) Swap(name, to string) error {
	i, ok := s.has(name)
	if !ok {
		return fmt.Errorf("not found, %v", name)
	}

	if _, ok = s.has(to); ok && name != to {
		return fmt.Errorf("already exists, %v", to)
	}

	s.order[i] = to
	return nil
}

func (s relativeOrder) Remove(name string) error {
	i, ok := s.has(name)
	if !ok {
		return fmt.Errorf("not found, %v", name)
	}

	s.order = append(s.order[:i], s.order[i+1:]...)
	return nil
}

func (s *relativeOrder) insert(i int, name string, pos RelativePosition) error {
	switch pos {
	case Before:
		s.order = append(s.order, "")
		copy(s.order[i+1:], s.order[i:])
		s.order[i] = name

	case After:
		s.order = append(s.order, name)

	default:
		return fmt.Errorf("invalid position, %v", int(pos))
	}

	return nil
}

func (s *relativeOrder) has(name string) (i int, found bool) {
	for i := 0; i < len(s.order); i++ {
		if s.order[i] == name {
			return i, true
		}
	}
	return 0, false
}

// GetOrder returns the order of the item.
func (s *relativeOrder) GetOrder() []string {
	order := make([]string, len(s.order))
	copy(order, s.order)

	return order
}
