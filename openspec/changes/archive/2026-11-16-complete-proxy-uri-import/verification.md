---
task_id: OUT-1794722656313653
change: complete-proxy-uri-import
commit_sha: 5b7d33fade930ed10131d1d84ee3a7cdc86367af
local: passed
local_evidence: "Exact 5b passed 389 Rust unit tests, Clippy with warnings denied, locked workspace all-targets check, ci-gates, architecture and metadata. Recovered Kotlin validation passed 1079 cases: 1068 fresh and 11 detection FROM-CACHE. Diagnostics-data lint/detekt and actual wire 18 accepted/54 rejected passed. Full static/app/service lint passed on production-identical C99 before the test-only 5b change. Receipts: /Users/npochaev/GitHub/RIPDPI-worktrees/imp01-evidence/integration."
remote_ci: passed
remote_ci_evidence: "Full manual CI 43804310691 completed SUCCESS on exact 5b: 70 jobs, 55 success, 15 routed skipped, zero failed/cancelled. ci-required, all three debug size jobs, all five emulator jobs, CodeQL, fleet, harness and Secret Scan passed. Compact proof: integration/approved-budget-hosted-ci-terminal.json, SHA256 674d28aafafcf1273ce4d73f01b8d0c21e9fe3c6227558fab7bc59088ccdba7c."
device: passed
device_evidence: "Hosted API27/33/35/36/37 emulator jobs passed on exact 5b. API35 passed independent Xray peer and real provider TUN steps. API37 passed actual instrumentation, Simple integration and Kaspresso smoke with the unchanged runner and 120-second install timeout. This does not establish physical-device or external TUIC/Mieru peer acceptance."
artifact: not_applicable
artifact_evidence: Source commits are the deliverable; no signed application or release is owned. Actual native file verification is recorded below.
deployment: not_applicable
deployment_evidence: No deployment or release is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-IMP01-F1 | OUT-1794723300448448 | ProxyUriCodecTest checks encoded security names, duplicate scalar names and retained raw escaping. | passed |
| REQ-IMP01-F2 | OUT-1794723305001424 | Real Room persistence and transaction tests check repeated imports, reopen, separate selection, export, rollback and cancellation. | passed |
| REQ-IMP01-F3 | OUT-1794723307980598 | Saved Mieru TCP protocol, multiplexing and MTU survive share and parse. | passed |
| REQ-IMP01-F4 | OUT-1794723312688319 | SIP002 SS2022 export matches an independently specified plain percent-encoded userinfo value. | passed |
| REQ-IMP01-F5 | OUT-1794723319097202 | SSH tests preserve literal plus credentials and reject malformed percent values. | passed |
| REQ-IMP01-F6 | OUT-1794723323595628 | Scanner tests assert redacted failures for all twelve URI schemes and aliases. | passed |
| REQ-IMP01-F7 | OUT-1794723325992640 | Shadowsocks scheme case variants reach the parser and return the same profile. | passed |
| REQ-IMP01-TUIC | OUT-1794723329887849 | Typed URI, real Room persistence, explicit selection, native config and lossless share tests pass. No live TUIC peer acceptance is claimed. | passed |
| REQ-IMP01-MIERU | OUT-1794723333000951 | Presence-aware protobuf and saved share tests pass. Pinned Go vectors and two real Go 1.27.4 loopback cases verify advanced TCP and Off shutdown; 389 native tests and Clippy passed on retained native inputs. | passed |
| REQ-IMP01-BACKUP | OUT-1794756875357718 | Upstream REDACTED SHARE policy is retained for credentials and identifying Mieru traffic patterns. FULL retains all 12 presence combinations. The reconciled policy passed 17 fresh backup tests, including sealed-type coverage; independent review approved. | passed |
| REQ-IMP01-ROUNDTRIP | OUT-1794758046683376 | Engine change preserves raw optional values. Fresh RED: 53 tests and 5 assertion failures; GREEN: 53 passed, independent review and hooks passed. Actual runtime normalization remains separate. | passed |
| REQ-IMP01-SELECTION | OUT-1794759937653382 | RED returned old no_wait for selected null. The fix removes only the two known prior Mieru keys before selected-profile merge. The 72-test engine selection set and dedicated 12-pair regression passed; independent review approved. | passed |

## Accepted source and complete hosted check

Accepted code and policy SHA: `5b7d33fade930ed10131d1d84ee3a7cdc86367af`. All requested fixes, TUIC URI support, advanced Mieru TCP settings and the approved native budget are ancestors of this published source.

[Full manual CI 43804310691](https://github.com/po4yka/RIPDPI/actions/runs/43804310691) completed SUCCESS: **55 successful jobs, 15 routed skips, zero failures or cancellations**. All three debug APK jobs passed the actual packaged native-size guard. All five emulator jobs passed; API35 passed the independent Xray peer and actual TUN steps, and API37 passed actual instrumentation, Simple integration and Kaspresso smoke. CodeQL, fleet, harness and Secret Scan passed on the same SHA. The run used normal default inputs and the full route; no optional soak, benchmark, root, physical-device or external-server lane was enabled.

Earlier push CI runs remain historical failures or cancellations. They are not relabelled GREEN. The full manual run has its own concurrency group and retained its exact SHA while later main pushes cancelled push-triggered runs. Compact terminal proof: `integration/approved-budget-hosted-ci-terminal.json`; full snapshot and scoped APK/API37 proofs are linked from that receipt.

## Final local evidence

| Check | Observed result and exact scope |
|---|---|
| Native units on 5b | 389 passed: Mieru 82, relay-core 258, TLS transports 49. Two unit opt-ins and five separate integration opt-ins remained ignored. They are excluded from the passed count. |
| Native checks on 5b | All-target Clippy for the three owned crates with warnings denied, locked workspace all-targets compile, ci-gates, architecture and locked metadata passed. |
| Kotlin on 5b | 1079 passed: 1068 fresh and 11 detection FROM-CACHE. Fresh counts: runtime-state 489, engine 354, diagnostics 23, service 82, app 19, diagnostics-data 101. No failures/errors/skips. The four updated DNS cancellation tests passed fresh within diagnostics-data. |
| Test-only delta on 5b | Diagnostics-data detekt and lint passed. Full staticAnalysis and explicit app/service lint had passed on C99, whose production sources are identical to 5b. |
| Actual wire on 5b | Current Kotlin export plus actual Rust decoder: 18 accepted, 54 invalid schemas rejected; one contract test passed, 17 unrelated cases filtered. |
| Approved size policy | 35 fresh existing Python tests and actual policy-delta validation passed. All eight real CI library sizes passed under the 800 KiB limit; old policy reproduced the prior failure. |
| Actual native ELF files | All 20 libraries across four ABIs passed. Exact NEEDED oracles cover main, tunnel and AmneziaWG; alignment/export checks cover all five libraries. Helper executables are outside this gate. |

Exact commands, source hashes and immutable results: `integration/final-native-5b-v1` and `integration/robolectric-cache-recovery-v1/CHECKPOINT.md`. The latter terminal receipt has SHA256 `8ab9770d85dc96f6a42530835b18a45c3025847120cb048d9d31f01837987bda`.

The earlier full `coverageReport` and `kotlinCoverageVerification` run passed 10421 fresh tests on `41129892bd4107f1eb5242555c6a47a2aecbc9aa`. Its source scope and all 13 executed test tasks remain recorded in `integration/final-frontend-v2-monitor-metrics.json`. The later 1079 cases overlap that set and are not added as distinct coverage. The full manual 5b coverage job passed, but no new test count or all-fresh claim is inferred from job status alone.

## Approved native budget

The user explicitly approved **720 to 800 KiB** per-library growth on 2026-11-16. Root commit `07cee2106e10df9f8a614022bdd21a413c20a4f0` (author `34faefe4a7bd54a69630f0b6f5c78461ddf34cd2`) changes only that effective limit and appends required approval provenance. All eight historical measured sizes, 4.7% and 3670016-byte total caps, bloat limits, source behavior, profiles and ABI coverage remain unchanged. Independent review approved the patch.

Original IMP-01 EE passed its size guard. Later upstream native-fetch/adaptive changes added 64432 packaged x86 bytes; geo added another 30120. Current follow-up and its prior base were byte-identical. The exact first crossing commit was not established. This was measured cumulative growth, not an unproved source optimization. Four downloaded CI ZIP digests matched metadata. Prior actual total was 79052792 versus allowed 80238131; the approved budget left 22748 x86 bytes. Fresh 5b APK guards subsequently passed. Proof: `integration/approved-native-budget-v1`, `integration/native-size-regression-v1` and `integration/approved-budget-main-publication.json`.

## Pinned peer and evidence limits

Actual local Go 1.27.4 peers at source `155ebbd60f86e472586a60d7ffe58ec8f8682cb1` verified advanced Mieru TCP and Off shutdown. The latter closed both independent Off carriers before server shutdown. Those concrete scenarios are separate from the ignored aggregate tests. Hosted interop evidence also records the pin and successful named scenarios, but no actual hosted Go-version metadata was available; it does not prove official Go 1.27.2 acceptance. The fresh native suite validates the later h3 vendor changes without promoting the retained peer proof into a new run.

Room proves durable ImportOnly save, reopen, rollback and later explicit selection. The native decoder proves configuration acceptance. Neither proves a live external TUIC connection. C means inspected code, V means the named observed run, S means a checked pinned requirement, and E means only the named peer/toolchain/scenario. Miri stopped at unsupported macOS kqueue and did not pass. No physical-device install, real-secret use, root activation, external TUIC server, pixel comparison, golden blessing or release is claimed.

## Preserved failures and recovery

The first published candidate exposed the backup allowlist, raw Mieru DTO and obsolete unsupported-TUIC fixture failures; these were corrected and their owning jobs passed later. FULL retains settings; SHARE omits credentials and identifying Mieru traffic patterns. Raw UI JSON retains optional presence, while runtime selection removes only the two prior Mieru keys before merging the selected profile. Upstream-equivalent diagnostics and PCAP loop fixes were retained instead of duplicating them. Disclosure changes affect tests only. The VPN class repair moves two private cleanup helpers with the same immutable controller; independent review and owning tests passed.

Infrastructure attempts are retained honestly. A fleet Maven HTTP429 failed before tests; one failed-jobs retry passed without dependency or mirror changes. A push coverage attempt failed on a Robolectric connection reset before class assertions. Local C99 validation blocked in a framework download and file lock; verified owned launcher termination preserved thread dumps. Exact existing framework JARs were SHA512-verified against official Maven Central and installed under a real Java FileChannel lock. The recovered 5b test run passed without app, dependency-version, TLS, Java IPv6, timeout or repository-URI changes.

Earlier API37 install hit the unchanged 120-second timeout before assertions. The subsequent exact 5b job passed with the same runner blob and timeout. Its secondary push execution used an existing internal retry after a first 'Process crashed'; no stack or cause was established. That history is not called a clean first attempt. Primary manual API37 succeeded; absence of retries is not inferred without a complete attempt record.

Upstream after accepted 5b added only Robolectric SDK preparation in build logic and the test SDK catalog. Application/native sources and the approved policy remain unchanged by that rebase. This later infrastructure is not authored as an IMP-01 source fix; its configuration/source checks are recorded separately before final integration.

## Final rebase checks

On `00b5d009e022eceb8e131611a060d8291c73fcae` above upstream e7b88, all four `final-rebase-config-v1` jobs passed: actual Gradle configuration/help, ci-gates, architecture and locked metadata. The help check compiles/configures the build but does not prove execution of SDK preparation. The separate hosted e7b88 Kotlin coverage job `133098024580` did execute `:prepareRobolectricSdks` without a cache/UP-TO-DATE marker at 04:51:59 UTC and completed SUCCESS at 05:00:18 UTC. Its build reported 494 tasks, 413 executed and 81 cached. This provides observed validation of the new test build logic; no new all-fresh test count or individual inventory of all twelve SDK artifacts is inferred. Receipt: `integration/e7-robolectric-buildlogic-observed.json`, SHA256 `d9a1cc993522741b80b11310839654beeeca3ae8db11367d7105168322883fda`. Overall e7b88 CI was still running at that read; the accepted full-code CI remains the exact 5b manual run.
