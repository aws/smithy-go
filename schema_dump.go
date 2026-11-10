package smithy

import "sort"

// SchemaDump is a view of a schema's contents, used to verify generated
// schemas.
type SchemaDump struct {
	ID, TargetID ShapeID
	Type         ShapeType
	IsMember     bool
	Indexed      []Trait
	Other        []Trait // sorted by trait ID
	DirectMask   uint64
}

// Dump returns the contents of the schema.
func (s *Schema) Dump() SchemaDump {
	d := SchemaDump{
		ID:       s.ID(),
		TargetID: s.TargetID(),
		Type:     s.Type(),
		IsMember: s.MemberName() != "",
	}

	if ts := s.d.Traits; ts != nil {
		d.Indexed = ts.Indexed
		d.DirectMask = ts.DirectMask
		d.Other = append(d.Other, ts.Other...)
	}

	sort.Slice(d.Other, func(i, j int) bool {
		return d.Other[i].TraitID().String() < d.Other[j].TraitID().String()
	})
	return d
}
