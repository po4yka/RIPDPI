---
task_id: ENG-1794672016726805
change: dpi30-tls-entropy-contracts
commit_sha: 37950cd088d7bdabc3baba09a62674bc69290baa
local: passed
local_evidence: 800 affected Rust tests, strict Clippy, ci-gates, architecture health and locked Cargo metadata passed.
remote_ci: not_applicable
remote_ci_evidence: Local repair acceptance; no hosted CI success is claimed.
device: not_applicable
device_evidence: Android arm64 all-targets compile-only passed; physical and emulator acceptance were not run.
artifact: passed
artifact_evidence: 26 final combined-code OpenSSL handshakes plus 6 no-SNI and multi-record peer handshakes passed; independent wire checks passed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI30-REAL | ENG-1794672216453080 | Runtime 194 tests; final 26 peer cases; genuine bytes unchanged; raw-unavailable Split receipts | passed |
| REQ-DPI30-JOINT | ENG-1794672217202181 | Packets and UDP final-byte metric regressions; runtime budget outcomes | passed |
| REQ-DPI30-BUDGET | ENG-1794672217918700 | Zero budget across generators and UDP fake caller | passed |
| REQ-DPI30-COMPOSE | ENG-1794672218548079 | Multi-record TLS12/13 and both preludes; exact reassembled bytes and peer handshakes | passed |
| REQ-DPI30-SNI | ENG-1794672219205759 | No-SNI override and malformed mandatory-vector guards; TLS12/13 handshakes | passed |
| REQ-DPI30-DOCS | ENG-1794672219849243 | Record minor vs negotiated TLS and set-bits/byte documentation reviewed | passed |

## Commands and observed results

From `native/rust/`, with disposable `CARGO_TARGET_DIR=/tmp/dpi30-f1-target`:

- `cargo test --locked -p ripdpi-packets -p ripdpi-desync -p ripdpi-desync-runtime -p ripdpi-privileged-ops`: 800 passed, zero failures or ignored tests. Packets 328 unit plus 5 integration; desync 237 unit plus 14 integration; runtime 194; privileged operations 22 on macOS.
- `cargo clippy --locked -p ripdpi-packets -p ripdpi-desync -p ripdpi-desync-runtime -p ripdpi-privileged-ops --all-targets -- -D warnings`: passed. Existing Cargo manifest warnings are separate from rustc/Clippy warnings; no lint setting was changed.
- `cargo check --locked --target aarch64-linux-android -p ripdpi-packets -p ripdpi-desync -p ripdpi-desync-runtime -p ripdpi-privileged-ops --all-targets`: passed with NDK 30.0.16248370 API24 clang/clang++/llvm-ar configured through command-local environment variables. Compile-only; no Android execution is claimed.

From the job root:

- `just ci-gates`: passed after implementation and again after rebase.
- `python3 scripts/ci/check_architecture_health.py`: passed before integration.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1`: passed.
- `python3 scripts/ci/check_rust_api_snapshots.py --update`: added only the no-SNI recognition export to packets; subsequent check passed. Linux-owned runtime-platform snapshot is skipped by the owning tool on macOS.

The rebase added only the unrelated orphaned liveness-fixture cleanup. `git diff --exit-code 78c289b0c7 HEAD -- native/rust` confirmed unchanged Rust inputs, so the 800-test and Android results remain valid for the recorded commit.

## Independent review

The read-only reviewer approved F1-F5 after corrections. Review found and resolved two additional gaps: splice truncated grown fake buffers, and no-SNI recognition needed strict mandatory-vector lengths. Runtime now preflights both primary and secondary growth; unavailable raw transport uses an explicit Split before any fake send. Native grown fakes select the existing raw path. No generic retry was added after a possibly committed operation. F6 is a small documentation correction reviewed by the integrator.

Each finding has its own implementation commit. F2 and F4 also have separate integration-regression commits. Planning and verification are separate lifecycle commits.

## Peer evidence and limits

- `python3 /tmp/dpi30-peer-final/peer-final.py`: 26 of 26 completed OpenSSL 3.5.5 TLS1.2/1.3 handshakes on final production code.
- `python3 /tmp/dpi30-peer-final/check-wire.py`: passed. Minor-only changes are limited to byte2; record preludes preserve handshake bodies; entropy modes preserve genuine bytes.
- `/tmp/dpi30-tls-peer.log`: six completed no-SNI/multi-record TLSRec/TLSRandRec handshakes after final parser guards.
- Snapshot provenance: `/tmp/dpi30-peer-final/source-manifest.json`, SHA256 `514d1c69e85652512913ab6a068f117739a785c193e9d2f4fb39ecb67e8f3d64`. Snapshot preceded the integration-test assertion correction; production inputs did not change. Original audit artifacts were preserved.

The peer test uses real loopback TCP bytes and an independent TLS engine, with the existing simulated fake platform. It does not prove a privileged raw sender on a physical device or external server compatibility. The Linux-specific capability-cleared native test was added but did not execute on macOS. No root activation, physical probe, external active probe, real secret, dependency, lockfile, locale, baseline or golden change was used.

A grown decoy requires raw capability. Non-root splice cannot grow fake sequence space; the app preserves connectivity through an explicit Split fallback. This is a supported degraded path, not a claim that entropy padding was emitted.

## Resolved validation failures

The first combined build reported a missing new export despite current source and API snapshot containing it. Package output cleanup and a forced cache refresh removed that stale host result. The combined suite then exposed an old runtime assertion that expected a joint solution inside an insufficient default budget; it now checks both insufficient and sufficient budgets with independent metrics. An Android attempt without configured compiler drivers and a host retry from the wrong toolchain directory were configuration failures; final commands above passed from the owning workspace with pinned toolchain and NDK.

Logs remain in `/tmp/dpi30-combined-tests-final.log`, `/tmp/dpi30-combined-clippy-final.log`, `/tmp/dpi30-android-check-final.log`, `/tmp/dpi30-rebased-ci-gates.log`, and the peer directories. No external E status is claimed.
