#!/usr/bin/env bash
#
# Signs and stages smithy-go-codegen, then uploads it to Central Portal
# as a pending (USER_MANAGED) deployment for manual review/publish at
# https://central.sonatype.com/publishing.
#
# Required environment variables (not read from any file on disk):
#   ORG_GRADLE_PROJECT_signingKey            ASCII-armored PGP private key
#   ORG_GRADLE_PROJECT_signingPassword       passphrase for the above key
#   ORG_GRADLE_PROJECT_mavenCentralUsername  Central Portal user token username
#   ORG_GRADLE_PROJECT_mavenCentralPassword  Central Portal user token password
#
# Usage:
#   export ORG_GRADLE_PROJECT_signingKey="$(cat private-key.asc)"
#   export ORG_GRADLE_PROJECT_signingPassword="..."
#   export ORG_GRADLE_PROJECT_mavenCentralUsername="..."
#   export ORG_GRADLE_PROJECT_mavenCentralPassword="..."
#   ./publish-to-central.sh

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

required_vars=(
    ORG_GRADLE_PROJECT_signingKey
    ORG_GRADLE_PROJECT_signingPassword
    ORG_GRADLE_PROJECT_mavenCentralUsername
    ORG_GRADLE_PROJECT_mavenCentralPassword
)

missing=()
for var in "${required_vars[@]}"; do
    if [[ -z "${!var:-}" ]]; then
        missing+=("$var")
    fi
done

if [[ ${#missing[@]} -gt 0 ]]; then
    echo "Missing required environment variables:" >&2
    printf '  %s\n' "${missing[@]}" >&2
    exit 1
fi

./gradlew clean publish uploadToCentralPortal

echo
echo "Uploaded. Review and publish (or drop) the deployment at:"
echo "  https://central.sonatype.com/publishing"
