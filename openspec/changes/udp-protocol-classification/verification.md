---
task_id: RST-1790681076965083
change: udp-protocol-classification
commit_sha: null
local: passed
local_evidence: Four-package Rust tests, affected Clippy, fmt, locked metadata, native architecture contracts, architecture health, and independent review passed; commands and limits below.
remote_ci: required
remote_ci_evidence: null
device: passed
device_evidence: Full ARM64 interceptor test binary ran on emulator-5556 API 37 with 16384-byte pages; 33 passed, zero failed, ignored or filtered. No app settings or policies changed.
artifact: passed
artifact_evidence: NDK 29.0.14206865 API 27 ARM64 test executable built with 16K LOAD alignment; 49016952 bytes, SHA256 e72c6f4836250f03bc823297a3771ac17e653761b293bd293815f4d96039d5d1. This is native module evidence, not an APK or root raw-socket acceptance claim.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UDP-001 | RST-1790681243879940 | Full packet-handler matrix on host and Android; QUIC v1/v2, neighboring protocols, unknown/short/DNS/MTProto, wrong ports, empty/Any, IPv4/IPv6 | passed |
| REQ-UDP-002 | RST-1790681243879940 | Actual TUN Lua execution for twelve protocol/subtype payloads; both DNS ports; retained QUIC version, hostname and marker tests | passed |
| REQ-UDP-003 | RST-1790681245112444 | Existing Lua socket-owner regression, architecture contracts, locked internal-only dependency edge, independent review without findings | passed |

## Observed local checks

The regression first failed before the implementation: a QUIC rule injected
STUN traffic. Evidence: `/tmp/ripdpi-udp-classification-red.log`.

From `native/rust`:

```bash
cargo test --locked -p ripdpi-tunnel-intercept -p ripdpi-protocol-detect -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-tunnel-core
cargo clippy --locked --no-deps --all-targets -p ripdpi-tunnel-intercept -p ripdpi-protocol-detect -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-tunnel-core -- -D warnings
cargo fmt --all --check
cargo metadata --locked --format-version 1
```

Tests: 415 passed, zero failures or filtered cases. One existing timing-sensitive
relay handshake test remains ignored by its source annotation. Linux-only
test binaries have no runnable cases on macOS; seven in-process TUN E2E tests
passed. Logs: `/tmp/ripdpi-udp-rust-test-module-split.log`,
`/tmp/ripdpi-udp-rust-clippy-module-split.log`, `/tmp/ripdpi-udp-rust-fmt-module-split-final.log`.

Native architecture contracts reported zero violations. Architecture health
reported 23 current and 23 baseline indicators with no new, worsened, or stale
entries. Cargo.lock adds only the existing workspace classifier dependency.
No dependency versions or baselines changed.

## Android native execution

```bash
cargo test --locked --target aarch64-linux-android --profile android-jni-dev -p ripdpi-tunnel-intercept --no-run --message-format json-render-diagnostics
```

The build used the owning Gradle task's NDK linker and environment. All 33
tests in the resulting executable ran on the actual API 37 ARM64 emulator,
including the final QUIC v2/version cases. Logs and exact environment:
`/tmp/ripdpi-udp-android-split-build.log`,
`/tmp/ripdpi-udp-android-split-test.log`,
`/tmp/ripdpi-udp-android-split-elf.log`,
`/tmp/ripdpi-udp-android-split-artifact.json`, and
`/tmp/ripdpi-udp-android-command.txt`. The device test directory was removed
and its absence verified. App state was unchanged.

These tests verify classification, selection, Lua context, and constructed
packets. They do not establish DPI evasion or successful privileged raw
transmission. The separate full-root Lua target gate remains outside this
change.

The existing egress test module was moved to `egress/tests.rs` to keep the
production source below the staged architecture size limit. Independent
review confirmed all previous test functions and 27 raw-string fixtures
remain intact. The split module passed the Rust and Android gates above.
