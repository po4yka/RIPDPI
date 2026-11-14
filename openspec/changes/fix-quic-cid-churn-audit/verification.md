---
task_id: ENG-1794637897912969
change: fix-quic-cid-churn-audit
commit_sha: 2ed3516b98469d9620b866660fe6c82fa5b46857
local: blocked
local_evidence: 765 Rust tests and 5 Kotlin tests passed after rebase, with no failures or ignored tests. Four-crate Clippy, locked metadata, source gates and independent review passed. Full staticAnalysis is blocked by Maven access and missing offline artifacts.
remote_ci: required
remote_ci_evidence: Pending the authorized main push; no hosted result is claimed.
device: not_applicable
device_evidence: No Android lifecycle change. Device, root, and external network acceptance are outside this local repair.
artifact: passed
artifact_evidence: TShark decoded 150 generated and received QUIC packets with no AEAD failures; the maximum IPv4 loopback regression preserved the original 65507-byte datagram.
deployment: not_applicable
deployment_evidence: No release or deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-CID-BUDGET | ENG-1794638032319330 | Kotlin/native 0/1/64/65/i32 maximum and aggregate count tests; defensive planner tests | Passed |
| REQ-CID-UNIQUE | ENG-1794638033677169 | v1/v2 private 260-packet old-boundary tests; two 32-step plan gives 64 distinct DCIDs; TShark | Passed |
| REQ-CID-LENGTH | ENG-1794638037254859 | Length transition targets, exact 65507 size, independent decode, real IPv4 loopback | Passed |
| REQ-CID-SCOPE | ENG-1794638039475187 | Existing activation/relay tests, original byte equality, independent review and source gates | Passed locally; full static analysis blocked |

## Observed commands

Cargo tests and Clippy ran from native/rust with pinned Rust 1.100.0 and an isolated worktree target. Heavy commands used build-gate with four workers. The owner explicitly approved temporary use of the rly14 lane for this repair.

- cargo test --locked --jobs 4 -p ripdpi-proxy-config -p ripdpi-desync -p ripdpi-packets -p ripdpi-proxy-runtime-desync-adapter: 765 passed, 0 failed, 0 ignored after rebase. Log: /tmp/ripdpi-dpi22-fix-evidence/rebased-tests.log.
- ./gradlew :core:data:model:testDebugUnitTest --tests com.poyka.ripdpi.data.StrategyChainValidationTest --offline --max-workers=4 -Pripdpi.nativeCpuBudget=4: 5 passed, 0 failed, 0 skipped after rebase. Heap limits: Gradle 5 GiB, Kotlin 3 GiB. Log: /tmp/ripdpi-dpi22-fix-evidence/rebased-kotlin.log.
- cargo clippy --locked --jobs 4 -p ripdpi-proxy-config -p ripdpi-desync -p ripdpi-packets -p ripdpi-proxy-runtime-desync-adapter --no-deps --all-targets -- -D warnings: passed. Log: /tmp/ripdpi-dpi22-fix-evidence/rebased-clippy.log.
- just ci-gates: passed after rebase, including architecture health, LoC/hotspot limits, cross-language contracts, fixture versions, locale/export checks and API snapshots. The Linux-owned runtime-platform API snapshot was skipped on macOS by its owning script. Log: /tmp/ripdpi-dpi22-fix-evidence/rebased-ci-gates.log.
- cargo +1.100.0 metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1: passed. No dependency, schema, golden, or quality baseline change was needed.
- Read-only pr-reviewer: no production findings before or after rebase. One overly broad size promise in planning text was corrected in both the requirement and scenario.
- Standard commit hooks passed for all three fix commits before rebase. The three conflict resolutions retained both dummy-prepend and CID-churn validation and planner budgets. Incoming DPI-17 tests were retained and rerun.

## Independent packet evidence

A temporary example called the production parser, planner, and UDP adapter. Its source and bytes remain under /tmp/ripdpi-dpi22-fix-evidence; the example was removed from the worktree after execution. TShark 4.6.9 independently decoded 75 frames per version, all PN 0, with no checktag failure. Raw byte checks found 64 distinct DCIDs across two 32-packet steps. Direct loopback received three decoys and the exact original; the upstream relay path received only the framed original. Transition targets 16406/16407 and maximum 65507 bytes had the exact expected size. Logs: rebased-wire.log and wire/*-tshark.tsv under the same temporary directory.

PCAP IP/UDP headers and checksums were synthetic wrappers around packet bytes. This proves QUIC packet protection and payload integrity, not NIC checksum capture, Android VPN behavior, remote server compatibility, or DPI efficacy. No physical device, external probe, root activation, or real secret was used.

## Unresolved full static analysis

./gradlew staticAnalysis failed before source analysis because Google Maven returned No route to host while resolving detekt-api 1.23.8. A second run with --offline failed because detekt-api, detekt-psi-utils, Kotlin compiler 2.0.21 and further detekt artifacts were absent from the cache. The configuration-cache serialization message was caused by this dependency-resolution failure. No quality check or repository setting was disabled. Logs: /tmp/ripdpi-dpi22-fix-evidence/static-analysis-rly14.log and static-analysis-offline.log.

Rerun ./gradlew staticAnalysis when the declared Maven repositories are reachable. Local acceptance remains blocked and the task is not archive-ready. No hosted CI result is inferred from these host checks.

## Commits

- 1266472276: bound CID churn configuration and planner work.
- adc5ea6cf2: remove CID collisions within one plan.
- 2ed3516b98: size Initial padding with the final Length width.

The original audit base was 74a434befe. Integration rebased onto d68a56399f and preserved the incoming DPI-17 changes.
