#!/usr/bin/env bash
set -euo pipefail

[[ "$#" -gt 0 ]] || {
  echo "Usage: $0 COMMAND [ARG ...]" >&2
  exit 2
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
managed_root="$repo_root/build/release-candidate-downloads"
mkdir -p "$managed_root"
download_dir="$(mktemp -d "$managed_root/download.XXXXXX")"

cleanup() {
  case "$download_dir" in
    "$managed_root"/download.*) rm -rf -- "$download_dir" ;;
    *) echo "Refusing unsafe transient cleanup: $download_dir" >&2; return 1 ;;
  esac
}
trap cleanup EXIT

export RIPDPI_RELEASE_DOWNLOAD_DIR="$download_dir"
"$@"
