module github.com/aws/smithy-go/aws-http-auth-schemes

go 1.24

require (
	github.com/aws/smithy-go v1.27.3
	github.com/aws/smithy-go/aws-http-auth v1.2.0
)

replace github.com/aws/smithy-go => ..

replace github.com/aws/smithy-go/aws-http-auth => ../aws-http-auth
