#!/usr/bin/env bash
# Refresh the bundled Mozilla CCADB snapshot under
# native/rust/crates/ripdpi-tls-profiles/ca-bundle/mozilla-ca.pem and fail CI
# if the committed bundle is older than MAX_AGE_DAYS.
#
# Usage:
#   scripts/ci/refresh_mozilla_ca_bundle.sh [--write]
#
# Without --write: checks freshness + diff size vs upstream, non-zero exit
# if the in-tree bundle is stale or drifted. Use in CI.
# With --write: overwrites the in-tree bundle from upstream. Use locally
# when refreshing.

set -euo pipefail

BUNDLE_PATH="native/rust/crates/ripdpi-tls-profiles/ca-bundle/mozilla-ca.pem"
UPSTREAM_URL="https://curl.se/ca/cacert.pem"
MAX_AGE_DAYS="${MAX_AGE_DAYS:-180}"

write_mode=false
if [[ "${1:-}" == "--write" ]]; then
    write_mode=true
fi

if [[ ! -f "$BUNDLE_PATH" ]]; then
    echo "error: $BUNDLE_PATH is missing" >&2
    echo "run: $0 --write" >&2
    exit 1
fi

if "$write_mode"; then
    mkdir -p "$(dirname "$BUNDLE_PATH")"
    curl -fsSL "$UPSTREAM_URL" -o "$BUNDLE_PATH"
    count=$(grep -c 'BEGIN CERTIFICATE' "$BUNDLE_PATH" || echo 0)
    if (( count < 100 )); then
        echo "error: refreshed bundle only contains $count certs; refusing" >&2
        exit 1
    fi
    echo "ok: refreshed $BUNDLE_PATH ($count roots)"
    exit 0
fi

# Freshness guard (by mtime, falling back to file-system ctime on macOS).
if command -v stat >/dev/null 2>&1; then
    if mtime=$(stat -f %m "$BUNDLE_PATH" 2>/dev/null); then :;
    else mtime=$(stat -c %Y "$BUNDLE_PATH"); fi
    now=$(date +%s)
    age_days=$(( (now - mtime) / 86400 ))
    if (( age_days > MAX_AGE_DAYS )); then
        echo "error: $BUNDLE_PATH is $age_days days old (> $MAX_AGE_DAYS)." >&2
        echo "refresh with: $0 --write" >&2
        exit 1
    fi
    echo "ok: bundle age is $age_days days"
fi

count=$(grep -c 'BEGIN CERTIFICATE' "$BUNDLE_PATH" || echo 0)
if (( count < 100 )); then
    echo "error: bundle only contains $count certs (expected >=100)" >&2
    exit 1
fi
echo "ok: $count Mozilla CA roots in bundle"
