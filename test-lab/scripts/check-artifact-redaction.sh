#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
target="${1:-$lab_root/artifacts}"

usage() {
  cat <<USAGE
Usage: $0 [PATH]

Scans a lab artifact directory or tar.gz archive for secret-looking keys that
must not appear in exported diagnostic handoff material.
USAGE
}

case "${1:-}" in
  -h|--help)
    usage
    exit 0
    ;;
esac

deny_pattern='\\b(token|password|auth|secret|private_key|ssid|bssid|imsi|operator|subscription)\\b[[:space:]]*[:=][[:space:]]*(?!<redacted>|null|false|true|0|\\[\\]|\\{\\}|[[:space:]]*(\\n|\\r|$))\\S+'
archive_tmp_dir=""

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
    ' $(find "$directory" -type f ! -name '*.key' ! -name 'lab.key' ! -name '*.tar.gz' ! -name '*.tgz' ! -path '*/.git/*' -print)
  )"
  if [[ -n "$matches" ]]; then
    printf '%s\n' "$matches"
    echo "Artifact redaction check failed for $directory" >&2
    return 1
  fi
}

scan_archive() {
  local archive="$1"
  archive_tmp_dir="$(mktemp -d)"
  trap 'rm -rf "$archive_tmp_dir"' EXIT
  tar -xzf "$archive" -C "$archive_tmp_dir"
  scan_directory "$archive_tmp_dir"
}

if [[ -f "$target" ]]; then
  case "$target" in
    *.tar.gz|*.tgz)
      scan_archive "$target"
      ;;
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

echo "Artifact redaction check passed: $target"
