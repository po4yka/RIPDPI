#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
checklist_path="${1:-$repo_root/docs/feature-test-checklist.md}"
evidence_path="${2:-$repo_root/docs/feature-test-evidence-2026-05-14.md}"

if [[ $# -gt 2 ]]; then
  echo "Usage: $0 [CHECKLIST_MD EVIDENCE_MD]" >&2
  exit 2
fi

python3 - "$checklist_path" "$evidence_path" <<'PY'
import re
import sys
from pathlib import Path


def normalize(value: str) -> str:
    value = value.lower().replace("&", "and")
    value = re.sub(r"[^a-z0-9]+", " ", value)
    return re.sub(r"\s+", " ", value).strip()


checklist_path = Path(sys.argv[1])
evidence_path = Path(sys.argv[2])
evidence_text = evidence_path.read_text(encoding="utf-8")

checklist_sections = []
checklist_item_counts = {}
current_section = None
for line in checklist_path.read_text(encoding="utf-8").splitlines():
    match = re.match(r"^(?:##|###)\s+(.+)$", line)
    if match:
        title = match.group(1).strip()
        if title != "Combination Matrices":
            checklist_sections.append(title)
            checklist_item_counts[title] = 0
            current_section = title
        else:
            current_section = None
        continue
    if re.match(r"^- \[ \]\s+", line) and current_section is not None:
        checklist_item_counts[current_section] += 1

allowed_statuses = {"Partial", "Covered locally"}
evidence_rows = []
invalid_rows = []
duplicate_rows = []
seen_evidence_keys = set()
command_evidence_rows = {}
for line in evidence_text.splitlines():
    if not line.startswith("|"):
        continue
    cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
    if len(cells) == 3 and cells[0] not in {"Check", "---"}:
        command_evidence_rows[cells[0]] = cells
        continue
    if len(cells) != 4 or cells[0] in {"Checklist section", "---"}:
        continue

    section, status, evidence, remaining_work = cells
    if status not in allowed_statuses:
        invalid_rows.append(f"{section}: unknown status {status!r}")
        continue
    if not evidence:
        invalid_rows.append(f"{section}: missing evidence summary")
    if status == "Partial" and remaining_work.lower() in {"", "none"}:
        invalid_rows.append(f"{section}: Partial row must name remaining work")
    if status == "Covered locally" and not remaining_work.lower().startswith("none"):
        invalid_rows.append(f"{section}: Covered locally row must start remaining work with 'None'")

    key = normalize(section)
    if key in seen_evidence_keys:
        duplicate_rows.append(section)
    seen_evidence_keys.add(key)
    evidence_rows.append(section)

evidence_by_key = {normalize(row): row for row in evidence_rows}
missing = [section for section in checklist_sections if normalize(section) not in evidence_by_key]

if missing:
    for section in missing:
        print(f"Missing evidence row for checklist section: {section}", file=sys.stderr)
    raise SystemExit(1)

extra = [
    row for row in evidence_rows
    if normalize(row) not in {normalize(section) for section in checklist_sections}
]
if extra:
    for row in extra:
        print(f"Evidence row does not match a checklist section: {row}", file=sys.stderr)
    raise SystemExit(1)

if duplicate_rows:
    for row in duplicate_rows:
        print(f"Duplicate evidence row for checklist section: {row}", file=sys.stderr)
    raise SystemExit(1)

if invalid_rows:
    for row in invalid_rows:
        print(f"Invalid checklist evidence row: {row}", file=sys.stderr)
    raise SystemExit(1)

item_total = sum(checklist_item_counts.values())
if evidence_path.name.startswith("feature-test-evidence-"):
    audit_row = command_evidence_rows.get("Checklist section coverage audit")
    expected_section_text = f"{len(checklist_sections)} checklist sections"
    expected_item_text = f"{item_total} checklist items"
    expected_evidence_text = f"{len(evidence_rows)} evidence rows"
    if audit_row is None:
        print("Missing command evidence row: Checklist section coverage audit", file=sys.stderr)
        raise SystemExit(1)
    audit_row_text = " | ".join(audit_row)
    expected_summary_parts = [
        expected_section_text,
        expected_item_text,
        expected_evidence_text,
    ]
    missing_summary_parts = [
        part for part in expected_summary_parts if part not in audit_row_text
    ]
    if missing_summary_parts:
        print(
            "Checklist section coverage audit row does not mention current "
            f"summary baselines: {', '.join(missing_summary_parts)}",
            file=sys.stderr,
        )
        raise SystemExit(1)

print(
    "Feature checklist coverage self-test passed: "
    f"{len(checklist_sections)} checklist sections, "
    f"{item_total} checklist items, "
    f"{len(evidence_rows)} evidence rows, {len(missing)} missing mappings."
)
PY
