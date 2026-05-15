#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
evidence_path="${1:-$repo_root/docs/feature-test-evidence-2026-05-14.md}"

if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [EVIDENCE_MD]" >&2
  exit 2
fi

python3 - "$repo_root" "$evidence_path" <<'PY'
import re
import sys
from pathlib import Path

repo_root = Path(sys.argv[1])
evidence_path = Path(sys.argv[2])
text = evidence_path.read_text(encoding="utf-8")

artifact_paths = sorted(set(re.findall(r"test-lab/artifacts/[^`\s;,)|]+", text)))
missing = [path for path in artifact_paths if not (repo_root / path).exists()]

if missing:
    for path in missing:
        print(f"Missing referenced test-lab artifact: {path}", file=sys.stderr)
    raise SystemExit(1)

print(
    "Feature artifact path self-test passed: "
    f"{len(artifact_paths)} referenced artifact paths, {len(missing)} missing."
)
PY
