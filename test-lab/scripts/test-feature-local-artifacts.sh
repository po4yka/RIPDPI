#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
evidence_path="${1:-$repo_root/docs/feature-test-evidence-2026-05-14.md}"
artifact_root="${RIPDPI_ARTIFACT_ROOT:-$repo_root}"

if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [EVIDENCE_MD]" >&2
  exit 2
fi

python3 - "$artifact_root" "$evidence_path" <<'PY'
import re
import sys
from pathlib import Path

repo_root = Path(sys.argv[1])
evidence_path = Path(sys.argv[2])
text = evidence_path.read_text(encoding="utf-8")

artifact_paths = set()
for value in re.findall(r"`([^`]+)`", text):
    normalized = value.removeprefix("$PWD/")
    if "$" in normalized:
        continue
    if normalized.startswith(("app/build/", "core/")) and normalized.endswith((".apk", ".xml")):
        artifact_paths.add(normalized)

for match in re.finditer(r"(?:\$PWD/)?((?:app|core)/[^`\s;,)|]+(?:\.apk|\.xml))", text):
    path = match.group(1)
    if "$" not in path:
        artifact_paths.add(path)

missing = []
empty = []
for path in sorted(artifact_paths):
    artifact = repo_root / path
    if not artifact.exists():
        missing.append(path)
    elif artifact.is_file() and artifact.stat().st_size == 0:
        empty.append(path)

for path in missing:
    print(f"Missing referenced local artifact: {path}", file=sys.stderr)
for path in empty:
    print(f"Empty referenced local artifact: {path}", file=sys.stderr)

if missing or empty:
    raise SystemExit(1)

print(
    "Feature local artifact self-test passed: "
    f"{len(artifact_paths)} referenced local artifacts, {len(missing)} missing, "
    f"{len(empty)} empty."
)
PY
