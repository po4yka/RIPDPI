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
default_report_package_specs=(
    ripdpi-ws-tunnel
    ripdpi-proxy-runtime
    ripdpi-monitor-proxy-runtime
    ripdpi-tunnel-core
    ripdpi-monitor-engine
    ripdpi-diagnostics-classification
    ripdpi-android
)
if [[ -n "${RIPDPI_RUST_COVERAGE_REPORT_PACKAGES+x}" ]]; then
    report_package_specs="$RIPDPI_RUST_COVERAGE_REPORT_PACKAGES"
else
    report_package_specs="${default_report_package_specs[*]}"
fi
if [[ -n "${RIPDPI_RUST_COVERAGE_TEST_PACKAGES+x}" ]]; then
    test_package_specs="$RIPDPI_RUST_COVERAGE_TEST_PACKAGES"
else
    test_package_specs="$report_package_specs"
fi

report_scope_args=()
if [[ -n "${report_package_specs//[[:space:]]/}" ]]; then
    for package in $report_package_specs; do
        report_scope_args+=(--package "$package")
    done
fi

test_scope_args=()
if [[ -n "${test_package_specs//[[:space:]]/}" ]]; then
    for package in $test_package_specs; do
        test_scope_args+=(--package "$package")
    done
else
    test_scope_args+=(--workspace)
fi

mkdir -p "$target_dir"

if ! cargo llvm-cov --locked --version >/dev/null 2>&1; then
    echo "cargo-llvm-cov is required. Install it with: cargo install cargo-llvm-cov" >&2
    exit 1
fi

# Tests that need CAP_NET_ADMIN (BPF attach, TCP window clamp) are excluded
# from CI — mirrors the nextest exclusions in run-rust-workspace-tests.sh.
SKIP_PATTERNS=(
    --skip 'platform::linux::tests::bpf_'
    --skip 'platform::linux::tests::tcp_window_clamp'
    --skip 'runtime::tests::window_clamp'
)

# These tests are timing-sensitive under cargo-llvm-cov instrumentation and workspace load. They remain covered by the normal workspace test lane, where they run without coverage instrumentation.
COVERAGE_ONLY_SKIP_PATTERNS=(
    --skip 'runtime::udp::tests::udp_upstream_poll_returns_only_ready_flow_keys'
    --skip 'tests::monitor_session_full_matrix_strategy_probe_reports_audit_assessment'
    --skip 'quic_handshake_and_echo_round_trip_through_socks5_udp_relay'
)

# Nightly coverage includes ignored tests for additional low-cost coverage, but
# real TUN and SO_BINDTODEVICE E2E require privileged runner state and are
# covered by the dedicated Linux TUN lanes instead.
IGNORED_SKIP_PATTERNS=(
    --skip 'real_tun_'
    --skip 'so_bindtodevice_'
)

run_coverage() {
    RUST_TEST_THREADS=1 cargo llvm-cov --locked test \
        --manifest-path "$workspace_manifest" \
        "${test_scope_args[@]}" \
        --no-report \
        -- \
        --test-threads=1 \
        "${SKIP_PATTERNS[@]}" \
        "${COVERAGE_ONLY_SKIP_PATTERNS[@]}"

    if [[ "$include_ignored" == "1" ]]; then
        RUST_TEST_THREADS=1 cargo llvm-cov --locked test \
            --manifest-path "$workspace_manifest" \
            "${test_scope_args[@]}" \
            --no-report \
            -- \
            --ignored \
            --test-threads=1 \
            "${IGNORED_SKIP_PATTERNS[@]}"
    fi
}

echo "==> rust coverage clean"
cargo llvm-cov --locked clean --manifest-path "$workspace_manifest" --workspace

echo "==> rust coverage run"
run_coverage

echo "==> rust coverage reports"
cargo llvm-cov --locked report \
    --manifest-path "$workspace_manifest" \
    "${report_scope_args[@]}" \
    --ignore-filename-regex "$ignore_regex" \
    --html \
    --output-dir "$html_dir"

cargo llvm-cov --locked report \
    --manifest-path "$workspace_manifest" \
    "${report_scope_args[@]}" \
    --ignore-filename-regex "$ignore_regex" \
    --lcov \
    --output-path "$lcov_path"

cargo llvm-cov --locked report \
    --manifest-path "$workspace_manifest" \
    "${report_scope_args[@]}" \
    --ignore-filename-regex "$ignore_regex" \
    --summary-only >"$summary_txt"

cargo llvm-cov --locked report \
    --manifest-path "$workspace_manifest" \
    "${report_scope_args[@]}" \
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
