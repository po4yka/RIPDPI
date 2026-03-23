#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"
target_dir="${RIPDPI_RUST_COVERAGE_DIR:-$repo_root/native/rust/target/coverage}"
html_dir="$target_dir/html"
lcov_path="$target_dir/lcov.info"
summary_txt="$target_dir/summary.txt"
summary_json="$target_dir/summary.json"
metrics_env="$target_dir/metrics.env"
ignore_regex="${RIPDPI_RUST_COVERAGE_IGNORE_REGEX:-.*/third_party/.*}"
critical_file_list="${RIPDPI_RUST_COVERAGE_CRITICAL_FILES:-$repo_root/scripts/ci/rust-coverage-critical-files.txt}"
min_line="${RIPDPI_RUST_COVERAGE_MIN_LINE:-78}"
enforce="${RIPDPI_ENFORCE_COVERAGE_THRESHOLDS:-0}"
include_ignored="${RIPDPI_RUST_COVERAGE_INCLUDE_IGNORED:-0}"

mkdir -p "$target_dir"

if ! cargo llvm-cov --version >/dev/null 2>&1; then
    echo "cargo-llvm-cov is required. Install it with: cargo install cargo-llvm-cov" >&2
    exit 1
fi

run_coverage() {
    RUST_TEST_THREADS=1 cargo llvm-cov test \
        --manifest-path "$workspace_manifest" \
        --workspace \
        --no-report \
        -- \
        --test-threads=1

    if [[ "$include_ignored" == "1" ]]; then
        RUST_TEST_THREADS=1 cargo llvm-cov test \
            --manifest-path "$workspace_manifest" \
            --workspace \
            --no-report \
            -- \
            --ignored \
            --test-threads=1
    fi
}

echo "==> rust coverage clean"
cargo llvm-cov clean --manifest-path "$workspace_manifest" --workspace

echo "==> rust coverage run"
run_coverage

echo "==> rust coverage reports"
cargo llvm-cov report \
    --manifest-path "$workspace_manifest" \
    --ignore-filename-regex "$ignore_regex" \
    --html \
    --output-dir "$html_dir"

cargo llvm-cov report \
    --manifest-path "$workspace_manifest" \
    --ignore-filename-regex "$ignore_regex" \
    --lcov \
    --output-path "$lcov_path"

cargo llvm-cov report \
    --manifest-path "$workspace_manifest" \
    --ignore-filename-regex "$ignore_regex" \
    --summary-only >"$summary_txt"

cargo llvm-cov report \
    --manifest-path "$workspace_manifest" \
    --ignore-filename-regex "$ignore_regex" \
    --json \
    --summary-only \
    --output-path "$summary_json"

python3 - "$summary_txt" "$summary_json" "$metrics_env" "$min_line" "$enforce" "$repo_root" "$critical_file_list" <<'PY'
import json
import sys
from pathlib import Path

summary_path = Path(sys.argv[1])
summary_json_path = Path(sys.argv[2])
metrics_path = Path(sys.argv[3])
min_line = float(sys.argv[4])
enforce = sys.argv[5] == "1"
repo_root = Path(sys.argv[6]).resolve()
critical_list_path = Path(sys.argv[7]).resolve()

text = summary_path.read_text()
if "TOTAL" not in text:
    raise SystemExit("Unable to find TOTAL coverage line in rust summary")

coverage = json.loads(summary_json_path.read_text())
data = coverage.get("data") or []
if not data:
    raise SystemExit("Rust coverage JSON report did not contain any data")

line_totals = data[0].get("totals", {}).get("lines")
if not isinstance(line_totals, dict):
    raise SystemExit("Rust coverage JSON report is missing line totals")

line_coverage = float(line_totals.get("percent"))
file_summaries = {
    Path(entry["filename"]).resolve(): float(entry["summary"]["lines"]["percent"])
    for entry in data[0].get("files", [])
}

critical_paths = []
for raw_line in critical_list_path.read_text().splitlines():
    line = raw_line.strip()
    if not line or line.startswith("#"):
        continue
    critical_paths.append((repo_root / line).resolve())

zero_critical = [path for path in critical_paths if file_summaries.get(path, 0.0) == 0.0]
metrics_path.write_text(
    "\n".join(
        [
            f"RUST_LINE_COVERAGE={line_coverage:.2f}",
            f"RUST_ZERO_CRITICAL_COUNT={len(zero_critical)}",
            "RUST_ZERO_CRITICAL_FILES=" + "|".join(str(path) for path in zero_critical),
        ]
    )
    + "\n"
)
print(f"Rust line coverage: {line_coverage:.2f}%")
if zero_critical:
    print("Critical files without Rust coverage:")
    for path in zero_critical:
        print(f"  - {path}")

if enforce and line_coverage < min_line:
    raise SystemExit(f"Rust line coverage {line_coverage:.2f}% is below required {min_line:.2f}%")
if enforce and zero_critical:
    raise SystemExit(
        "Critical Rust network files must not remain at 0% coverage:\n"
        + "\n".join(str(path) for path in zero_critical)
    )
PY

echo "Reports:"
echo "  HTML: $html_dir"
echo "  LCOV: $lcov_path"
echo "  Summary: $summary_txt"
echo "  JSON: $summary_json"
