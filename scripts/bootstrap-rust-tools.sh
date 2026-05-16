#!/usr/bin/env bash
# bootstrap-rust-tools.sh — install (idempotently) all Rust developer tools
# referenced by the RIPDPI harness skills.
#
# Tools and the skill that mentions them:
#   cargo-careful, loom, proptest, cargo-fuzz,
#   cargo-mutants                                   → rust-test-tools
#   cargo-audit, cargo-deny                         → rust-security
#   cargo-bloat, cargo-llvm-lines, cargo-flamegraph → rust-performance
#   cargo-machete, cargo-udeps                      → cargo-workflows
#   cargo-semver-checks                             → (optional; for future
#                                                       public-crate plans)
#   miri, rust-src                                  → rust-sanitizers-miri
#
# Usage:
#   scripts/bootstrap-rust-tools.sh           # install everything
#   scripts/bootstrap-rust-tools.sh --check   # report missing; exit 1 if any
#   scripts/bootstrap-rust-tools.sh --skip-nightly  # skip nightly components
#
# Disable a specific tool:
#   RIPDPI_SKIP_TOOLS="cargo-fuzz,cargo-mutants" scripts/bootstrap-rust-tools.sh

set -uo pipefail

CHECK_ONLY=0
SKIP_NIGHTLY=0
for arg in "$@"; do
  case "$arg" in
    --check) CHECK_ONLY=1 ;;
    --skip-nightly) SKIP_NIGHTLY=1 ;;
    --help|-h)
      sed -n '1,/^$/p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "unknown arg: $arg (try --help)" >&2
      exit 2
      ;;
  esac
done

SKIP_LIST="${RIPDPI_SKIP_TOOLS:-}"
is_skipped() {
  case ",${SKIP_LIST}," in
    *",$1,"*) return 0 ;;
    *) return 1 ;;
  esac
}

has_cargo_subcommand() {
  cargo "$1" --version >/dev/null 2>&1
}

install_cargo_tool() {
  local name="$1"
  local cmd="$2"  # the cargo subcommand to test for
  local extra="${3:-}"  # additional cargo install args
  if is_skipped "$name"; then
    echo "  $name  SKIPPED (RIPDPI_SKIP_TOOLS)"
    return 0
  fi
  if has_cargo_subcommand "$cmd"; then
    echo "  $name  OK"
    return 0
  fi
  if [[ $CHECK_ONLY -eq 1 ]]; then
    echo "  $name  MISSING"
    return 1
  fi
  echo "  $name  installing ..."
  # shellcheck disable=SC2086
  cargo install --locked $extra "$name" >/dev/null 2>&1 || {
    echo "  $name  FAILED — try: cargo install --locked $extra $name" >&2
    return 1
  }
  echo "  $name  installed"
}

echo "=== RIPDPI Rust tool bootstrap ==="
echo

missing=0

echo "Stable cargo subcommands:"
install_cargo_tool cargo-careful careful || missing=$((missing+1))
install_cargo_tool cargo-audit audit || missing=$((missing+1))
install_cargo_tool cargo-deny deny || missing=$((missing+1))
install_cargo_tool cargo-bloat bloat || missing=$((missing+1))
install_cargo_tool cargo-machete machete || missing=$((missing+1))
install_cargo_tool cargo-mutants mutants || missing=$((missing+1))
install_cargo_tool cargo-llvm-lines llvm-lines || missing=$((missing+1))
install_cargo_tool cargo-flamegraph flamegraph || missing=$((missing+1))
install_cargo_tool cargo-semver-checks semver-checks || missing=$((missing+1))

echo
echo "Nightly-only cargo subcommands:"
if [[ $SKIP_NIGHTLY -eq 1 ]]; then
  echo "  (skipped per --skip-nightly)"
else
  install_cargo_tool cargo-fuzz fuzz "" || missing=$((missing+1))
  install_cargo_tool cargo-udeps +nightly\ udeps "" || missing=$((missing+1))
fi

echo
echo "Nightly toolchain components:"
if [[ $SKIP_NIGHTLY -eq 1 ]]; then
  echo "  (skipped per --skip-nightly)"
elif ! command -v rustup >/dev/null 2>&1; then
  echo "  rustup not in PATH — skipping miri / rust-src component install" >&2
  missing=$((missing+1))
else
  for component in miri rust-src; do
    if rustup +nightly component list --installed 2>/dev/null | grep -q "^${component}"; then
      echo "  $component  OK (nightly)"
    elif [[ $CHECK_ONLY -eq 1 ]]; then
      echo "  $component  MISSING (nightly)"
      missing=$((missing+1))
    else
      echo "  $component  installing for nightly ..."
      rustup +nightly component add "$component" >/dev/null 2>&1 || {
        echo "  $component  FAILED — try: rustup +nightly component add $component" >&2
        missing=$((missing+1))
      }
    fi
  done
fi

echo
if [[ $missing -gt 0 ]]; then
  if [[ $CHECK_ONLY -eq 1 ]]; then
    echo "DRIFT: $missing tool(s) missing — re-run without --check to install"
    exit 1
  else
    echo "WARNING: $missing tool(s) failed to install — see lines above"
    exit 1
  fi
fi

echo "All Rust developer tools present and current."
exit 0
