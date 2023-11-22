// Package requestcompression implements runtime support for smithy-modeled
// request compression.
//
// This package is designated as private and is intended for use only by the
// smithy client runtime. The exported API therein is not considered stable and
// is subject to breaking changes without notice.
package requestcompression

import (
	"bytes"
	"compress/gzip"
	"context"
	"fmt"
	"github.com/aws/smithy-go/middleware"
	"github.com/aws/smithy-go/transport/http"
	"io"
	"strings"
)

const maxRequestMinCompressSizeBytes = 10485760

// Enumeration values for supported compress Algorithms.
const (
	GZIP = "gzip"
)

type compressFunc func(io.Reader) ([]byte, error)

var allowedAlgorithms = map[string]compressFunc{
	GZIP: gzipCompress,
}

// AddRequestCompression add requestCompression middleware to op stack
func AddRequestCompression(stack *middleware.Stack, DisableRequestCompression bool, RequestMinCompressSizeBytes int64, algorithms string) error {
	return stack.Serialize.Add(&requestCompression{
		disableRequestCompression:   DisableRequestCompression,
		requestMinCompressSizeBytes: RequestMinCompressSizeBytes,
		compressAlgorithms:          strings.Split(algorithms, ","),
	}, middleware.After)
}

type requestCompression struct {
	disableRequestCompression   bool
	requestMinCompressSizeBytes int64
	compressAlgorithms          []string
}

// ID returns the ID of the middleware
func (m requestCompression) ID() string {
	return "RequestCompression"
}

// HandleSerialize gzip compress the request's stream/body if enabled by config fields
func (m requestCompression) HandleSerialize(
	ctx context.Context, in middleware.SerializeInput, next middleware.SerializeHandler,
) (
	out middleware.SerializeOutput, metadata middleware.Metadata, err error,
) {
	if m.disableRequestCompression {
		return next.HandleSerialize(ctx, in)
	}
	// still need to check requestMinCompressSizeBytes in case it is out of range after service client config
	if m.requestMinCompressSizeBytes < 0 || m.requestMinCompressSizeBytes > maxRequestMinCompressSizeBytes {
		return out, metadata, fmt.Errorf("invalid range for min request compression size bytes %d, must be within 0 and 10485760 inclusively", m.requestMinCompressSizeBytes)
	}

	req, ok := in.Request.(*http.Request)
	if !ok {
		return out, metadata, fmt.Errorf("unknown request type %T", req)
	}

	for _, algorithm := range m.compressAlgorithms {
		compressFunc := allowedAlgorithms[algorithm]
		if compressFunc != nil {
			if stream := req.GetStream(); stream != nil {
				size, found, err := req.StreamLength()
				if err != nil {
					return out, metadata, fmt.Errorf("error while finding request stream length, %v", err)
				} else if !found || size < m.requestMinCompressSizeBytes {
					return next.HandleSerialize(ctx, in)
				}

				compressedBytes, err := compressFunc(stream)
				if err != nil {
					return out, metadata, fmt.Errorf("failed to compress request stream, %v", err)
				}

				var newReq *http.Request
				if newReq, err = req.SetStream(bytes.NewReader(compressedBytes)); err != nil {
					return out, metadata, fmt.Errorf("failed to set request stream, %v", err)
				}
				*req = *newReq
				req.Header.Add("Content-Encoding", algorithm)
			}
			break
		}
	}

	return next.HandleSerialize(ctx, in)
}

func gzipCompress(input io.Reader) ([]byte, error) {
	var b bytes.Buffer
	w, err := gzip.NewWriterLevel(&b, gzip.DefaultCompression)
	if err != nil {
		return nil, fmt.Errorf("failed to create gzip writer, %v", err)
	}

	inBytes, err := io.ReadAll(input)
	if err != nil {
		return nil, fmt.Errorf("failed read payload to compress, %v", err)
	}

	if _, err = w.Write(inBytes); err != nil {
		return nil, fmt.Errorf("failed to write payload to be compressed, %v", err)
	}
	if err = w.Close(); err != nil {
		return nil, fmt.Errorf("failed to flush payload being compressed, %v", err)
	}

	return b.Bytes(), nil
}
