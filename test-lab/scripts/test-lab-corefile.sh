#!/usr/bin/env bash
set -euo pipefail

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
lab_root="$tmpdir/lab"
mkdir -p "$lab_root/dns"

# shellcheck source=test-lab/scripts/lab-corefile.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lab-corefile.sh"

printf 'corefile\n' > "$lab_root/dns/Corefile.active"
remove_generated_corefile_active
if [[ -e "$lab_root/dns/Corefile.active" ]]; then
  echo "Corefile.active file was not removed" >&2
  exit 1
fi

mkdir "$lab_root/dns/Corefile.active"
remove_generated_corefile_active
if [[ -e "$lab_root/dns/Corefile.active" ]]; then
  echo "Empty Corefile.active directory was not removed" >&2
  exit 1
fi

mkdir "$lab_root/dns/Corefile.active"
printf 'unexpected\n' > "$lab_root/dns/Corefile.active/child"
if remove_generated_corefile_active > "$tmpdir/corefile-test.out" 2> "$tmpdir/corefile-test.err"; then
  echo "Non-empty Corefile.active directory was removed unexpectedly" >&2
  exit 1
fi
if [[ ! -d "$lab_root/dns/Corefile.active" ]]; then
  echo "Non-empty Corefile.active directory should remain for inspection" >&2
  exit 1
fi

echo "Lab Corefile.active cleanup self-test passed."
