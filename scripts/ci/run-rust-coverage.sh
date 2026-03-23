#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")"/../.. && pwd)"
workspace_manifest="$repo_root/native/rust/Cargo.toml"
target_dir="${RIPDPI_RUST_COVERAGE_DIR:-$repo_root/native/rust/target/coverage}"
html_dir="$target_dir/html"
lcov_path="$target_dir/lcov.info"
summary_txt="$target_dir/summary.txt"
metrics_env="$target_dir/metrics.env"
ignore_regex="${RIPDPI_RUST_COVERAGE_IGNORE_REGEX:-.*/third_party/.*}"
min_line="${RIPDPI_RUST_COVERAGE_MIN_LINE:-70}"
enforce="${RIPDPI_ENFORCE_COVERAGE_THRESHOLDS:-0}"

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

python3 - "$summary_txt" "$metrics_env" "$min_line" "$enforce" <<'PY'
import re
import sys
from pathlib import Path

summary_path = Path(sys.argv[1])
metrics_path = Path(sys.argv[2])
min_line = float(sys.argv[3])
enforce = sys.argv[4] == "1"

text = summary_path.read_text()
total_line = next((line for line in text.splitlines() if line.strip().startswith("TOTAL")), None)
if total_line is None:
    raise SystemExit("Unable to find TOTAL coverage line in rust summary")

percentages = [float(match) for match in re.findall(r"(\d+(?:\.\d+)?)%", total_line)]
if len(percentages) >= 3:
    line_coverage = percentages[2]
elif percentages:
    line_coverage = percentages[0]
else:
    raise SystemExit(f"Unable to parse percentage values from rust summary line: {total_line}")

metrics_path.write_text(f"RUST_LINE_COVERAGE={line_coverage:.2f}\n")
print(f"Rust line coverage: {line_coverage:.2f}%")

if enforce and line_coverage < min_line:
    raise SystemExit(f"Rust line coverage {line_coverage:.2f}% is below required {min_line:.2f}%")
PY

echo "Reports:"
echo "  HTML: $html_dir"
echo "  LCOV: $lcov_path"
echo "  Summary: $summary_txt"
