---
task_id: ENG-1794498355495843
change: dpi01-write-contracts
commit_sha: b9e726c588b9e121fe43657d970c6f5d9c8b4ed2
local: passed
local_evidence: Six Rust library suites passed 1108 tests; four optimized overflow tests, six-crate Clippy, source gates, task contracts, and independent merge review passed. Full Gradle evidence is bound to 5a8eb74228.
remote_ci: not_applicable
remote_ci_evidence: No hosted CI result is used for local acceptance.
device: not_applicable
device_evidence: Host loopback tests do not establish Android or Linux device, root, or packet-boundary acceptance.
artifact: not_applicable
artifact_evidence: No release artifact is accepted. The successful JNI and helper builds belong to the full Gradle run at 5a8eb74228.
deployment: not_applicable
deployment_evidence: No release or deployment is requested.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SPLIT-ADMISSION | ENG-1794498598070219 | CLI and native JSON reject unsupported Split repeat/skip; Kotlin marker and chain tests pass. TLS record repetition remains accepted. | passed |
| REQ-SPLIT-ADMISSION | ENG-1794498598952395 | Native offset parser rejects fourth and later fields. Config library suite: 186 tests. | passed |
| REQ-SPLIT-ARITHMETIC | ENG-1794498599846554 | Desync library: 166 tests; four exact regressions also pass with optimization and overflow checks disabled. | passed |
| REQ-SPLIT-PROGRESS | ENG-1794498600585784 | Real TCP peer bytes equal the source prefix; error count equals received bytes. Both plain fallback error receipts and exact progress beyond the bounded receipt are checked. | passed |
| REQ-SPLIT-CANCEL | ENG-1794498601285650 | In-flight socket shutdown preserves a source prefix. A stopped first-write handler opens no new loopback connection. Known progress, uncertain commit, and BrokenPipe remain terminal. | passed |
| REQ-SPLIT-DELAY | ENG-1794498602365858 | The special executor sends the original six bytes and takes at least the configured 80 ms before completion. | passed |
| REQ-SPLIT-SCOPE | ENG-1794498603252152 | Independent read-only review, six-package tests and Clippy, just ci-gates, and just task-check pass. Gradle scope and platform gaps are stated below. | passed |

## Source revisions and integration

The final Rust and source checks ran at `b9e726c588b9e121fe43657d970c6f5d9c8b4ed2`, on base `f337f6e607da6d2d88902e459e321c159860e68e`.

The full Gradle command passed at `5a8eb74228c641a7f1a8bccc99f683f485c151c5`: 11 minutes 19 seconds, 853 tasks, 246 executed, 30 from cache, and 577 up to date. All four native build tasks passed. The core data XML reports 991 tests, zero failures, errors, and skips.

A later Rust-only rebase imported the DPI-04 uncertain-send contract. It did not change Kotlin, JNI exports, schemas used by Kotlin, dependencies, or build wiring. Merge review retained that uncertainty flag, its tag, and the original payload in the activation-skipped branch. A test literal gained the required false flag. The affected Rust and source checks were then repeated. No final Android binary at `b9e726c588` is claimed.

The earlier rebase imported payload-end clamping. The fallback regression now uses a negative offset that resolves to -1. The prior test input failed after that rebase; the corrected input passed and still checks both fallback branches. Both upstream clamp tests and the extreme-offset regression remain present.

## Commands and observed results

All compiler-backed commands used the machine build gate in the `ripdpi` lane. Cargo outputs were isolated to this worktree. Direct Cargo resolution used `--locked`; compiler jobs were at most four, or two for optimized checks.

From `native/rust/`:

```sh
cargo test --locked --jobs 4 -p ripdpi-config -p ripdpi-proxy-config -p ripdpi-desync -p ripdpi-desync-runtime -p ripdpi-proxy-runtime -p ripdpi-proxy-runtime-desync-adapter --lib
cargo clippy --locked --jobs 4 -p ripdpi-config -p ripdpi-proxy-config -p ripdpi-desync -p ripdpi-desync-runtime -p ripdpi-proxy-runtime -p ripdpi-proxy-runtime-desync-adapter --no-deps --all-targets -- -D warnings
cargo rustc --locked --jobs 2 -p ripdpi-desync --lib --profile test --message-format=json -- -C opt-level=2 -C overflow-checks=off
```

The six library suites passed 186, 157, 166, 170, 393, and 36 tests in the package order of the first command: 1108 total, zero failures or ignored tests. Clippy passed.

The optimized runner selected the emitted test binary from Cargo JSON in this worktree's target directory. It ran each of these names with `--exact` and required exactly one passing test per invocation:

- `tests::offset::gen_offset_rejects_overflow_for_payload_and_protocol_markers`
- `tests::offset::gen_offset_rejects_overflow_for_random_payload_cursor`
- `tests::plan_tcp::plan_tcp_rejects_extreme_marker_offsets_without_panicking`
- `tests::tls_prelude::tls_prelude_rejects_offset_adjustment_underflow`

From the repository root:

```sh
just ci-gates
just task-check
./gradlew staticAnalysis :core:data:testDebugUnitTest --max-workers=4 -Pripdpi.nativeCpuBudget=4
```

The first two commands passed at the final Rust revision. The Gradle revision is stated above. The Rust API gate checked 12 snapshots; the thirteenth, `ripdpi-runtime-platform`, is Linux-owned and was explicitly skipped on Darwin. No baseline, API snapshot, golden fixture, dependency version, or schema was changed by these repairs.

A temporary Cargo launcher added `--locked` to the API script's metadata command. The installed `cargo-public-api` frontend does not accept that option and internally invokes nightly rustdoc without it; that existing tool invocation is not represented as locked. Stable and nightly `cargo metadata --locked` checks passed, and Cargo.lock stayed unchanged. The temporary launcher's initial mise recursion failed before API validation; its corrected absolute Cargo path allowed the unchanged source gate to pass.

## Review and evidence limits

Independent review found no remaining production issue after each merge. Review kept upstream uncertain-send handling, payload-end clamp and round gates, fake-family receipts, and TTL cleanup. A missing field in one test literal and the old fallback-test precondition were corrected before the final checks.

C is current source review. V is limited to the stated host tests and native builds. The socket tests use actual peer bytes and the delay test uses a monotonic clock. They do not prove packet segmentation on a target network. S remains the requirements evidence in the original DPI-01 audit; these repairs do not add a wire standard. E remains unestablished.

No physical device, root activation, external endpoint, real secret, non-idempotent external request, or packet capture was used. Android/Linux cancellation and final packet boundaries still require target acceptance. The classifier and shutdown guard prevent replay for the covered conditions; a full server action-count test is not claimed.

Unsupported Split repetition is rejected rather than implemented. BrokenPipe now prevents automatic TFO retry even when reported progress is zero; this is a deliberate conservative choice. Zero-progress ConnectionReset controls and pre-write capability fallback remain covered.

The original audit's steady-state write-stall figure of five seconds was incorrect: the existing constant is 300 seconds in production and one second in tests. No timeout change was made.
