#!/usr/bin/env bash
# Download/build inputs are prepared before offline runtime acceptance.
set -euo pipefail
if [[ "$(uname -s)" != Linux || "${EUID}" -ne 0 ]]; then
    echo 'Run in the task-owned Linux VM as root.' >&2
    exit 1
fi
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends rustup jq tshark cmake libclang-dev build-essential pkg-config libssl-dev
export CARGO_HOME=/var/cache/ripdpi-acceptance/cargo
export RUSTUP_HOME=/var/cache/ripdpi-acceptance/rustup
export CARGO_TARGET_DIR=/var/cache/ripdpi-acceptance/target
RUSTUP_TOOLCHAIN="$(python3 -c 'import sys,tomllib; print(tomllib.load(open(sys.argv[1],"rb"))["toolchain"]["channel"])' "$repo_root/native/rust/rust-toolchain.toml")"
export RUSTUP_TOOLCHAIN
mkdir -p "$CARGO_HOME" "$RUSTUP_HOME" "$CARGO_TARGET_DIR"
# Ubuntu supplies rustup in /usr/bin; its proxy updater still expects this
# entry under the explicit test-only CARGO_HOME.
mkdir -p "$CARGO_HOME/bin"
if [[ ! -e "$CARGO_HOME/bin/rustup" ]]; then
    ln -s /usr/bin/rustup "$CARGO_HOME/bin/rustup"
fi
rustup toolchain install "$RUSTUP_TOOLCHAIN" --profile minimal
toolchain_bin="$(rustup run "$RUSTUP_TOOLCHAIN" rustc --print sysroot)/bin"
export PATH="$toolchain_bin:$PATH"
cargo fetch --locked --manifest-path "$repo_root/native/rust/Cargo.toml"
cargo test --locked --manifest-path "$repo_root/native/rust/Cargo.toml" -p ripdpi-cli --test packet_smoke --no-run
