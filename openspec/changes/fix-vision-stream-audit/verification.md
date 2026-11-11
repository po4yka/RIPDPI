---
task_id: TRN-1794371406782186
change: fix-vision-stream-audit
commit_sha: ae7427e5bb0c55ba435389078c449f3efe399edb
local: passed
local_evidence: 174 unit tests, 2 exact-pin loopback integration tests, Clippy, formatting, ci-gates, architecture health, locked metadata and independent reviews passed.
remote_ci: required
remote_ci_evidence: Exact-source hosted CI is not yet observed.
device: not_applicable
device_evidence: Host-native stream fixes; no Android device behavior changes.
artifact: not_applicable
artifact_evidence: No shipped binary or golden fixture is changed by this source task.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VISION-TLS | TRN-1794371482913552 | Five new detector tests failed before the fix; 18 Vision tests passed after it. Independent pr-reviewer and async audit passed. | Passed |
| REQ-VISION-EOF | TRN-1794371484334469 | Four decoder tests passed for read sizes 1/2/5/16/8192 and truncated headers/content/padding. | Passed |
| REQ-VISION-FALLBACK | TRN-1794371485290832 | Two fallback tests failed before the fix and passed after it; matching short prefixes and open-peer mismatches are covered. | Passed |
| REQ-VISION-LIFECYCLE | TRN-1794371486084547 | Three actual authenticated REALITY/TLS and TCP tests passed. H3 did not require a production change. Unknown commands retain tested upstream-compatible End fallback. | Passed |
| REQ-VISION-LIFECYCLE | TRN-1794371586756488 | Both empty-read tests failed before the fix; all four fallback/empty-read tests passed after it. | Passed |

Combined validation is owned by TRN-1794371486865436.

## Local commands and results

All compiler-backed checks ran under `build-gate --lane ripdpi`, with Cargo `--locked` and two jobs. The code revision above contains all four fix commits and the lifecycle test commit. Later task-only commits do not change these tested source inputs.

- `cargo test --locked -p ripdpi-vless --jobs 2`: 174 unit tests passed; eight external-fixture tests were ignored in the default run; no doctests exist.
- `cargo clippy --locked -p ripdpi-vless --no-deps --all-targets --jobs 2 -- -D warnings`: passed through the repository cargo-guarded wrapper.
- `cargo fmt --all -- --check`: passed.
- `just ci-gates`: passed all source gates; no baseline or generated export was changed.
- `python3 scripts/ci/check_architecture_health.py`: passed.
- `cargo metadata --locked --manifest-path native/rust/Cargo.toml --format-version 1 --no-deps`: passed, 113 workspace members. Manifests and lockfile are unchanged.
- Separate `upstream_interop` tests `vision_fragmented_tls_exchanges_payload_with_upstream` and `xudp_two_targets_exchange_with_upstream`, each with `-- --ignored --exact --nocapture`: one test passed per run. Peer source: Xray commit `12ee51e4bb1d02ece4ef4b7114efa2bcdc130995`; peer binary SHA256 `f13dea171a4fc0129db6327ffdf404df7b1aac2a7bd1916847bf1843ed174c13`. All endpoints and temporary credentials belong to loopback fixtures. Six other external-fixture tests remain unexecuted.
- Independent pr-reviewer passes covered detector, decoder, fallback, empty reads and lifecycle tests. Async audit found no missing annotations or cancellation defects after the test-only accept deadline was added.

Local logs are in `/tmp/trn02-fixed-evidence/`. Red reproductions are in `/tmp/trn02-tls-red.log`, `/tmp/trn02-decoder-f2-red.log` and `/tmp/trn02-decoder-f3-red.log`. Evidence uses observed nonzero test counts. A redundant queued final decoder run was cancelled; it is not counted as passing.

## Scope disposition

F1, F2 and F3 are fixed in separate commits. A fourth commit fixes the zero-capacity AsyncRead defect found during implementation. H1 is covered by structured record/handshake reassembly and split tests. H2 retains an explicit compatible tolerant-command policy. H3 passed real carrier characterization tests and required no production change. Wire and peer-test pin meanings are documented separately in SPEC_VERSION.md; no pin was changed.

This is host and loopback evidence. No physical device, public server, real credentials, root activation, release artifact or deployment was used. Remote CI remains a separate required gate for task closure; this change stays in review until that evidence exists.

## Rebased source check

The branch was rebased onto origin/main `c3ebd14b10f6778d7946bd2425cb501dec037a64`, which adds separate gRPC repairs in ripdpi-xhttp. At the exact commit recorded above, the 174 unit tests, Clippy, both exact-pin loopback tests, ci-gates, architecture health and locked metadata all passed again. Logs use the `rebased-` prefix in the same local evidence directory. No conflict resolution changed Vision source.
