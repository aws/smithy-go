#!/bin/bash

THRESHOLD=75 # percentage

go test -coverprofile c.out ./...
cov=$(go tool cover -func c.out  | grep '^total:' | awk '{ print $3+0 }')
if [[ $(bc --expression="$cov < $THRESHOLD") ]]; then
    echo "threshold not met - code must be at or above $THRESHOLD% coverage"
    exit 1
fi
