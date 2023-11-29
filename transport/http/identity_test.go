package http

import (
	"context"
	"testing"
	smithy "github.com/aws/smithy-go"
	"github.com/aws/smithy-go/auth"
)

func TestIdentity(t *testing.T) {
	var expected auth.Identity = &auth.AnonymousIdentity{}

	resolver := auth.AnonymousIdentityResolver{}
	actual, _ := resolver.GetIdentity(context.TODO(), smithy.Properties{})
	if expected != actual {
		t.Errorf("Anonymous identity resolver does not produce correct identity")
	}
}