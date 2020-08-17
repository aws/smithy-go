package httpbinding

import "testing"

func TestUserAgentBuilder(t *testing.T) {
	b := NewUserAgentBuilder()
	b.AddVersionedComponent("foo", "1.2.3")
	b.AddComponent("baz")
	if e, a := "foo/1.2.3 baz", b.Build(); e != a {
		t.Errorf("expect %v, got %v", e, a)
	}
}
