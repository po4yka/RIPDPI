#!/usr/bin/env bash
# export-strings-for-translation.sh -- regenerate the canonical translatable-key
# manifest from source.
#
# Extracts every translatable <string name="..."> key from the two source string
# resource directories, EXCLUDING any string carrying translatable="false", sorts -u, and
# writes the result to config/i18n/translatable-keys.txt. App keys are prefixed
# with "app:" and core/service keys with "service:".
#
# The manifest is the contract between the in-repo source strings and the
# translation-export pipeline. check-translation-export.sh diffs a fresh
# extraction against the committed manifest so CI fails when a source string is
# added or removed without the manifest being regenerated.
#
# Idempotent and runnable from the repository root:
#   scripts/ci/export-strings-for-translation.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO_ROOT"

OUT_DIR="config/i18n"
# The checker overrides the output path to compare an independently regenerated manifest.
OUT_FILE="${1:-$OUT_DIR/translatable-keys.txt}"

python3 - "$OUT_FILE" <<'PY_EXPORT'
import pathlib
import sys
import xml.etree.ElementTree as ET

keys = set()
for prefix, resource_dir in (
    ("app", "app/src/main/res/values"),
    ("service", "core/service/src/main/res/values"),
):
    directory = pathlib.Path(resource_dir)
    if not directory.is_dir():
        raise SystemExit(f"ERROR: source resource directory not found: {directory}")
    seen = set()
    for source in sorted(directory.glob("*.xml")):
        for resource in ET.parse(source).getroot():
            if resource.tag != "string":
                continue
            name = resource.attrib["name"]
            if name in seen:
                raise SystemExit(f"ERROR: duplicate source string key: {prefix}:{name}")
            seen.add(name)
            if resource.get("translatable") != "false":
                keys.add(f"{prefix}:{name}")

output = pathlib.Path(sys.argv[1])
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text("\n".join(sorted(keys)) + "\n", encoding="utf-8")
print(f"Wrote {len(keys)} translatable keys to {output}")
PY_EXPORT
