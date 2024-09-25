package sigv4

import (
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/aws/smithy-go/aws-http-auth/credentials"
	v4internal "github.com/aws/smithy-go/aws-http-auth/internal/v4"
	v4 "github.com/aws/smithy-go/aws-http-auth/v4"
)

var credsSession = credentials.Credentials{
	AccessKeyID:     "AKID",
	SecretAccessKey: "SECRET",
	SessionToken:    "SESSION",
}

var credsNoSession = credentials.Credentials{
	AccessKeyID:     "AKID",
	SecretAccessKey: "SECRET",
}

type signAll struct{}

func (signAll) IsSigned(string) bool { return true }

func seekable(v string) io.ReadSeekCloser {
	return readseekcloser{strings.NewReader(v)}
}

func nonseekable(v string) io.ReadCloser {
	return io.NopCloser(strings.NewReader(v)) // io.NopCloser elides Seek()
}

type readseekcloser struct {
	io.ReadSeeker
}

func (readseekcloser) Close() error { return nil }

func newRequest(body io.ReadCloser, opts ...func(*http.Request)) *http.Request {
	// we initialize via NewRequest because it sets basic things like host and
	// proto and is generally how we recommend the signing APIs are used
	//
	// the url doesn't actually need to match the signing name / region
	req, err := http.NewRequest(http.MethodPost, "https://service.region.amazonaws.com", body)
	if err != nil {
		panic(err)
	}

	for _, opt := range opts {
		opt(req)
	}
	return req
}

func expectSignature(t *testing.T, signed *http.Request, expectSignature, expectDate, expectToken string) {
	if actual := signed.Header.Get("Authorization"); expectSignature != actual {
		t.Errorf("expect signature:\n%s\n!=\n%s", expectSignature, actual)
	}
	if actual := signed.Header.Get("X-Amz-Date"); expectDate != actual {
		t.Errorf("expect date: %s != %s", expectDate, actual)
	}
	if actual := signed.Header.Get("X-Amz-Security-Token"); expectToken != actual {
		t.Errorf("expect token: %s != %s", expectToken, actual)
	}
}

func TestSignRequest(t *testing.T) {
	for name, tt := range map[string]struct {
		Input           *SignRequestInput
		Opts            v4.SignerOption
		ExpectSignature string
		ExpectDate      string
		ExpectToken     string
	}{
		"minimal case, nonseekable": {
			Input: &SignRequestInput{
				Request:     newRequest(nonseekable("{}")),
				Credentials: credsSession,
				Service:     "dynamodb",
				Region:      "us-east-1",
				Time:        time.Unix(0, 0),
			},
			ExpectSignature: "AWS4-HMAC-SHA256 Credential=AKID/19700101/us-east-1/dynamodb/aws4_request, SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=671ed63777ad2f28bfefd733087414652c1498b3301d9bdf272e44a3172c28c0",
			ExpectDate:      "19700101T000000Z",
			ExpectToken:     "SESSION",
		},
		"minimal case, seekable": {
			Input: &SignRequestInput{
				Request:     newRequest(seekable("{}")),
				Credentials: credsSession,
				Service:     "dynamodb",
				Region:      "us-east-1",
				Time:        time.Unix(0, 0),
			},
			ExpectSignature: "AWS4-HMAC-SHA256 Credential=AKID/19700101/us-east-1/dynamodb/aws4_request, SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=e75efbd4e2b3d3a8218d8fc0125e8fc888844510125ca6f33be555fd76d9aa18",
			ExpectDate:      "19700101T000000Z",
			ExpectToken:     "SESSION",
		},
		"minimal case, no session": {
			Input: &SignRequestInput{
				Request:     newRequest(nonseekable("{}")),
				Credentials: credsNoSession,
				Service:     "dynamodb",
				Region:      "us-east-1",
				Time:        time.Unix(0, 0),
			},
			ExpectSignature: "AWS4-HMAC-SHA256 Credential=AKID/19700101/us-east-1/dynamodb/aws4_request, SignedHeaders=host;x-amz-date, Signature=6f249a4b86fd230f28cae603cdf92c2657b1d1ffc3fcccbd938e1339c4542e14",
			ExpectDate:      "19700101T000000Z",
			ExpectToken:     "",
		},
		"explicit unsigned payload": {
			Input: &SignRequestInput{
				Request:     newRequest(seekable("{}")),
				PayloadHash: []byte(v4.UnsignedPayload),
				Credentials: credsSession,
				Service:     "dynamodb",
				Region:      "us-east-1",
				Time:        time.Unix(0, 0),
			},
			ExpectSignature: "AWS4-HMAC-SHA256 Credential=AKID/19700101/us-east-1/dynamodb/aws4_request, SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=671ed63777ad2f28bfefd733087414652c1498b3301d9bdf272e44a3172c28c0",
			ExpectDate:      "19700101T000000Z",
			ExpectToken:     "SESSION",
		},
		"explicit payload hash": {
			Input: &SignRequestInput{
				Request:     newRequest(seekable("{}")),
				PayloadHash: v4internal.Stosha("{}"),
				Credentials: credsSession,
				Service:     "dynamodb",
				Region:      "us-east-1",
				Time:        time.Unix(0, 0),
			},
			ExpectSignature: "AWS4-HMAC-SHA256 Credential=AKID/19700101/us-east-1/dynamodb/aws4_request, SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=e75efbd4e2b3d3a8218d8fc0125e8fc888844510125ca6f33be555fd76d9aa18",
			ExpectDate:      "19700101T000000Z",
			ExpectToken:     "SESSION",
		},
		"sign all headers": {
			Input: &SignRequestInput{
				Request: newRequest(seekable("{}"), func(r *http.Request) {
					r.Header.Set("Content-Type", "application/json")
					r.Header.Set("Foo", "bar")
					r.Header.Set("Bar", "baz")
				}),
				PayloadHash: v4internal.Stosha("{}"),
				Credentials: credsSession,
				Service:     "dynamodb",
				Region:      "us-east-1",
				Time:        time.Unix(0, 0),
			},
			Opts: func(o *v4.SignerOptions) {
				o.HeaderRules = signAll{}
			},
			ExpectSignature: "AWS4-HMAC-SHA256 Credential=AKID/19700101/us-east-1/dynamodb/aws4_request, SignedHeaders=bar;content-type;foo;host;x-amz-date;x-amz-security-token, Signature=90673d8f57147fd36dbde4d4fe156f643ea25627e7b4d14c157c6369e685b80a",
			ExpectDate:      "19700101T000000Z",
			ExpectToken:     "SESSION",
		},
		"disable implicit payload hash": {
			Input: &SignRequestInput{
				Request:     newRequest(seekable("{}")),
				Credentials: credsSession,
				Service:     "dynamodb",
				Region:      "us-east-1",
				Time:        time.Unix(0, 0),
			},
			Opts: func(o *v4.SignerOptions) {
				o.DisableImplicitPayloadHashing = true
			},
			ExpectSignature: "AWS4-HMAC-SHA256 Credential=AKID/19700101/us-east-1/dynamodb/aws4_request, SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=671ed63777ad2f28bfefd733087414652c1498b3301d9bdf272e44a3172c28c0",
			ExpectDate:      "19700101T000000Z",
			ExpectToken:     "SESSION",
		},
		"s3 settings": {
			Input: &SignRequestInput{
				Request:     newRequest(seekable("{}")),
				Credentials: credsSession,
				Service:     "s3",
				Region:      "us-east-1",
				Time:        time.Unix(0, 0),
			},
			Opts: func(o *v4.SignerOptions) {
				o.DisableDoublePathEscape = true
				o.AddPayloadHashHeader = true
			},
			ExpectSignature: "AWS4-HMAC-SHA256 Credential=AKID/19700101/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token, Signature=0232da513d9e9830b12cf0d9f374834671494bc362ee173adb2a50267d0339e0",
			ExpectDate:      "19700101T000000Z",
			ExpectToken:     "SESSION",
		},
	} {
		t.Run(name, func(t *testing.T) {
			opt := tt.Opts
			if opt == nil {
				opt = func(o *v4.SignerOptions) {}
			}
			signer := New(opt)
			if err := signer.SignRequest(tt.Input); err != nil {
				t.Fatalf("expect no err, got %v", err)
			}

			req := tt.Input.Request
			expectSignature(t, req, tt.ExpectSignature, tt.ExpectDate, tt.ExpectToken)
			if host := req.Header.Get("Host"); req.Host != host {
				t.Errorf("expect host header: %s != %s", req.Host, host)
			}
		})
	}
}
