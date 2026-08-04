#!/usr/bin/env bash
set -euo pipefail

lab_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
repo_root="$lab_root/.."
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
archive_dir="$lab_root/artifacts"
bundle_name="test-lab-artifacts-$timestamp"
work_dir="$(mktemp -d)"
stage_dir="$work_dir/$bundle_name"
adb_bin="${ADB:-adb}"
retention_class="public-sanitized"
raw_capture_sources=()

usage() {
  cat <<USAGE
Usage: $0 [--output DIR] [--retention-class CLASS]

Collects probe JSON, lab environment, Docker Compose service state/logs,
optional packet captures, and adb logcat/dumpsys output when adb is available.
The resulting archive is written to test-lab/artifacts/*.tar.gz by default.
CLASS defaults to public-sanitized, which excludes packet captures. Use
--retention-class private-raw-pcap explicitly for local-only captures retained
for at most seven days.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output)
      archive_dir="${2:?missing --output value}"
      shift 2
      ;;
    --retention-class)
      retention_class="${2:?missing --retention-class value}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

case "$retention_class" in
  public-sanitized|private-raw-pcap) ;;
  *) echo "Unsupported lab archive retention class: $retention_class" >&2; exit 2 ;;
esac

allowed_archive_dir="$lab_root/artifacts"
mkdir -p "$allowed_archive_dir"
if [[ -L "$archive_dir" ]] || \
   [[ "$(cd "$archive_dir" 2>/dev/null && pwd -P)" != "$(cd "$allowed_archive_dir" && pwd -P)" ]]; then
  echo "Archive output must be the policy-managed root: $allowed_archive_dir" >&2
  exit 2
fi
archive_dir="$allowed_archive_dir"
mkdir -p "$stage_dir"
trap 'rm -rf "$work_dir"' EXIT

copy_if_exists() {
  local source="$1"
  local dest_dir="$2"
  if [[ -e "$source" ]]; then
    mkdir -p "$dest_dir"
    cp -R "$source" "$dest_dir/"
  fi
}

compose_command() {
  if docker compose version >/dev/null 2>&1; then
    printf 'docker compose'
  elif command -v docker-compose >/dev/null 2>&1; then
    printf 'docker-compose'
  elif [[ -x /Applications/Docker.app/Contents/Resources/cli-plugins/docker-compose ]]; then
    printf '/Applications/Docker.app/Contents/Resources/cli-plugins/docker-compose'
  else
    return 1
  fi
}

redact_file() {
  local input="$1"
  local output="$2"
  perl -pe 's/\b(token|password|auth|secret|private_key|ssid|bssid|imsi|operator|subscription)\b(\s*[:=]\s*)\S*/$1$2<redacted>/ig; s#https?://\S+#<url-redacted>#g' \
    "$input" > "$output"
}

{
  echo "created_utc=$timestamp"
  echo "lab_root=$lab_root"
  echo "hostname=$(hostname 2>/dev/null || true)"
  echo "user=$(id -un 2>/dev/null || true)"
} > "$stage_dir/manifest.txt"

copy_if_exists "$lab_root/artifacts/lab-env.sh" "$stage_dir/artifacts"

shopt -s nullglob
for probe_json in "$lab_root"/artifacts/probe*.json "$lab_root"/artifacts/*probe*.json; do
  copy_if_exists "$probe_json" "$stage_dir/artifacts"
done

if [[ "$retention_class" == "private-raw-pcap" ]]; then
  for capture_file in "$lab_root"/capture/*.pcap "$lab_root"/capture/*.pcapng; do
    copy_if_exists "$capture_file" "$stage_dir/capture"
    raw_capture_sources+=("$capture_file")
  done
fi
for capture_file in "$lab_root"/capture/*.log; do
  copy_if_exists "$capture_file" "$stage_dir/capture"
done
shopt -u nullglob

if compose_cmd="$(compose_command)"; then
  (
    cd "$lab_root"
    $compose_cmd ps > "$stage_dir/docker-compose-ps.txt" 2>&1 || true
    $compose_cmd logs --no-color --timestamps > "$stage_dir/docker-compose-logs.txt" 2>&1 || true
  )
else
  echo "Docker Compose command not found; skipped compose logs." > "$stage_dir/docker-compose-skipped.txt"
fi

if command -v "$adb_bin" >/dev/null 2>&1; then
  adb_dir="$stage_dir/adb"
  mkdir -p "$adb_dir"
  "$adb_bin" devices -l > "$adb_dir/devices.txt" 2>&1 || true
  if "$adb_bin" get-state >/dev/null 2>&1; then
    "$adb_bin" logcat -d > "$adb_dir/logcat.raw.txt" 2>&1 || true
    if [[ -f "$adb_dir/logcat.raw.txt" ]]; then
      redact_file "$adb_dir/logcat.raw.txt" "$adb_dir/logcat.redacted.txt"
      rm -f "$adb_dir/logcat.raw.txt"
    fi
    "$adb_bin" shell dumpsys connectivity > "$adb_dir/dumpsys-connectivity.raw.txt" 2>&1 || true
    "$adb_bin" shell dumpsys vpn > "$adb_dir/dumpsys-vpn.raw.txt" 2>&1 || true
    if [[ -f "$adb_dir/dumpsys-connectivity.raw.txt" ]]; then
      redact_file "$adb_dir/dumpsys-connectivity.raw.txt" "$adb_dir/dumpsys-connectivity.txt"
      rm -f "$adb_dir/dumpsys-connectivity.raw.txt"
    fi
    if [[ -f "$adb_dir/dumpsys-vpn.raw.txt" ]]; then
      redact_file "$adb_dir/dumpsys-vpn.raw.txt" "$adb_dir/dumpsys-vpn.txt"
      rm -f "$adb_dir/dumpsys-vpn.raw.txt"
    fi
  else
    echo "adb is installed, but no device is connected." > "$adb_dir/skipped.txt"
  fi
else
  echo "adb not found; skipped logcat and dumpsys." > "$stage_dir/adb-skipped.txt"
fi

find "$stage_dir" \( \
  -iname '*.key' -o \
  -iname '*private*key*' -o \
  -iname 'lab.key' -o \
  -path '*/tls/certs/*' \
\) -type f -delete

archive_path="$archive_dir/$bundle_name.tar.gz"
tar -C "$work_dir" -czf "$archive_path" "$bundle_name"

echo "Created artifact archive: $archive_path"
"$lab_root/scripts/check-artifact-redaction.sh" \
  --retention-class "$retention_class" \
  "$archive_path"
python3 "$repo_root/scripts/ci/evidence_retention.py" \
  --policy "$repo_root/quality/evidence-retention.json" \
  write-manifest \
  --retention-class "$retention_class" \
  "$archive_path"
if [[ "$retention_class" == "private-raw-pcap" && "${#raw_capture_sources[@]}" -gt 0 ]]; then
  rm -f -- "${raw_capture_sources[@]}"
fi
