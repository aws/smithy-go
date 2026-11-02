package smithy

import "sort"

// SchemaDump is a form-independent view of a schema's contents, used by the
// static schema generator and its verification.
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

	if m := s.m; m != nil {
		d.Indexed = m.indexed
		d.DirectMask = m.directMask
		for _, t := range m.traits {
			d.Other = append(d.Other, t)
		}
	} else {
		ts := &s.set.Traits[s.rec().Traits]
		d.Indexed = ts.Indexed
		d.DirectMask = ts.DirectMask
		d.Other = append(d.Other, ts.Other...)
	}

	sort.Slice(d.Other, func(i, j int) bool {
		return d.Other[i].TraitID().String() < d.Other[j].TraitID().String()
	})
	return d
}
