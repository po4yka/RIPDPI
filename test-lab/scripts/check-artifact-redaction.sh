#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="${1:-$lab_root/artifacts}"
retention_class="public-sanitized"

usage() {
  cat <<USAGE
Usage: $0 [--retention-class CLASS] [PATH]

Scans a lab artifact directory or tar.gz archive for secret-looking keys that
must not appear in exported diagnostic handoff material.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --retention-class) retention_class="${2:?missing retention class}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "Unknown argument: $1" >&2; exit 2 ;;
    *) target="$1"; shift ;;
  esac
done

deny_pattern='\\b(token|password|auth|secret|private_key|ssid|bssid|imsi|operator|subscription)\\b[[:space:]]*[:=][[:space:]]*(?!<redacted>|null|false|true|0|\\[\\]|\\{\\}|[[:space:]]*(\\n|\\r|$))\\S+'

scan_directory() {
  local directory="$1"
  if [[ ! -d "$directory" ]]; then
    echo "Directory not found: $directory" >&2
    return 2
  fi
  local matches
  matches="$(
    perl -0ne '
      while (m/'"$deny_pattern"'/ig) {
        my $prefix = substr($_, 0, $-[0]);
        my $line = 1 + ($prefix =~ tr/\n//);
        print "$ARGV:$line:$&\n";
      }
    ' $(find "$directory" -type f ! -name '*.key' ! -name 'lab.key' ! -name '*.pcap' ! -name '*.pcapng' ! -name '*.tar.gz' ! -name '*.tgz' ! -path '*/.git/*' -print)
  )"
  if [[ -n "$matches" ]]; then
    printf '%s\n' "$matches"
    echo "Artifact redaction check failed for $directory" >&2
    return 1
  fi
}

if [[ -f "$target" ]]; then
  case "$target" in
    *.tar.gz|*.tgz) ;;
    *)
      echo "Unsupported artifact file type: $target" >&2
      exit 2
      ;;
  esac
elif [[ -d "$target" ]]; then
  scan_directory "$target"
else
  echo "Path not found: $target" >&2
  exit 2
fi

if [[ -f "$target" ]]; then
  python3 "$lab_root/../scripts/ci/evidence_retention.py" \
    --policy "$lab_root/../quality/evidence-retention.json" \
    check-archive \
    --retention-class "$retention_class" \
    "$target"
fi

echo "Artifact redaction check passed: $target"
