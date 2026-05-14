#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

python3 - "$repo_root/docs/feature-test-checklist.md" \
  "$repo_root/docs/feature-test-evidence-2026-05-14.md" <<'PY'
import re
import sys
from pathlib import Path


def normalize(value: str) -> str:
    value = value.lower().replace("&", "and")
    value = re.sub(r"[^a-z0-9]+", " ", value)
    return re.sub(r"\s+", " ", value).strip()


checklist_path = Path(sys.argv[1])
evidence_path = Path(sys.argv[2])

checklist_sections = []
for line in checklist_path.read_text(encoding="utf-8").splitlines():
    match = re.match(r"^(?:##|###)\s+(.+)$", line)
    if match:
        title = match.group(1).strip()
        if title != "Combination Matrices":
            checklist_sections.append(title)

evidence_rows = []
for line in evidence_path.read_text(encoding="utf-8").splitlines():
    match = re.match(r"^\|\s*([^|]+?)\s*\|\s*(?:Partial|Covered locally)\s*\|", line)
    if match and match.group(1).strip() != "Checklist section":
        evidence_rows.append(match.group(1).strip())

evidence_by_key = {normalize(row): row for row in evidence_rows}
missing = [section for section in checklist_sections if normalize(section) not in evidence_by_key]

if missing:
    for section in missing:
        print(f"Missing evidence row for checklist section: {section}", file=sys.stderr)
    raise SystemExit(1)

print(
    "Feature checklist coverage self-test passed: "
    f"{len(checklist_sections)} checklist sections, "
    f"{len(evidence_rows)} evidence rows, {len(missing)} missing mappings."
)
PY
