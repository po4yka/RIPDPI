---
task_id: RLY-1794335337545384
change: fix-relay-chain-audit-regressions
commit_sha: daf54b58643f6b3ecf5efaf1a3336769172e5aed
local: passed
local_evidence: Rebased tree passed 66 Kotlin tests, 319 Rust tests, focused Clippy, full staticAnalysis and ci-gates. Locked Cargo metadata and architecture checks also passed.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI was not run or claimed for these local regressions.
device: not_applicable
device_evidence: Host mapper, admission, public telemetry and real loopback carrier tests cover these changes. Physical VPN and underlying-network acceptance are not claimed.
artifact: not_applicable
artifact_evidence: No release binary, JNI generated output or golden baseline changed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-CHAIN-PERSIST | RLY-1794335430947778 | ConnectionChainRelayPersistenceTest and ConnectionRelayArtifactRepositoryTest; saved and settings routes match for new, edited, reordered and removed middle hops | Passed |
| REQ-CHAIN-ADMISSION | RLY-1794335431416389 | ConnectionChainRelayValidationTest and ChainRelaySelectionValidationTest; blank, missing and unsupported middle selections fail without shortening the route | Passed |
| REQ-CHAIN-ROLES | RLY-1794335431945519 | Relay-core snapshot retry/concurrency tests and chain_runtime_reports_only_attempted_hops_after_entry_failure through real runtime.telemetry() | Passed |
| REQ-CHAIN-TRUST | RLY-1794335432498689 | ConnectionChainRelayUiStateTest; middle-only shared or missing trust metadata and independent full domains | Passed |
| REQ-CHAIN-SHUTDOWN | RLY-1794335433023467 | Real TCP EOF tests for idle entry, active supplied transport, stalled TLS, cancelled cleanup retry, multi-thread shutdown and rejected supplied transport without pooling | Passed |

## Observed commands

All compiler commands used build-gate --lane ripdpi. Gradle used four workers and an isolated GRADLE_USER_HOME; direct Cargo used --locked --jobs 4, sccache and this worktree's target directory with incremental compilation disabled. Gradle dependency artifacts were copied with APFS clones into the isolated home. No repository, dependency version or source gate was changed to obtain a pass.

- `./gradlew :app:testGithubFullDebugUnitTest --tests '*ChainRelay*' --tests '*ConnectionRelayArtifactRepositoryTest*' :core:service:testDebugUnitTest --tests '*ChainRelay*' -Pripdpi.skipNativeBuild=true --max-workers=4 --console=plain --offline --continue`: exit 0. App: 41 tests in four suites. Service: 25 tests in three suites. No failures or skipped tests. Repeated after the mapper fallback simplification: exit 0.
- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-relay-core -p ripdpi-anytls -p ripdpi-relay-tls-transports --locked --jobs 4 --lib`: exit 0. AnyTLS: 27 passed; relay-core: 249 passed; TLS transports: 43 passed. Two existing relay-core upstream-peer tests remain ignored: Mieru and VLESS xHTTP XUDP. No new test is ignored; their external compatibility is not claimed.
- `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-relay-core -p ripdpi-anytls -p ripdpi-relay-tls-transports --locked --jobs 4 --all-targets -- -W clippy::await_holding_lock -W clippy::await_holding_refcell_ref -W clippy::await_holding_invalid_type -D warnings`: exit 0.
- `./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true --max-workers=4 --console=plain --offline`: exit 0. The added profile field first exceeded the existing method limit; the fallback expression was simplified and the gate repeated. Baselines and limits did not change.
- `just ci-gates`: exit 0, including Rust API snapshot checks. No snapshot drift required an update.
- `python3 scripts/ci/check_architecture_health.py --check`: exit 0.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1`: exit 0, 113 workspace members; manifests and lockfile remain unchanged.

Detailed local logs and source hashes are in `/tmp/rly16-verify/`. Compiler inputs match the code commit above; the final mapper-only simplification had its affected Kotlin tests and static analysis repeated. Later task-document commits do not change these source inputs. Temporary logs are supporting local evidence, not portable release receipts.

## Review and failure evidence

Independent pr-reviewer passes covered F1/F3, F4/F5 and F2. Review found a public telemetry fallback that still fabricated exit success and a supplied-session rejection that re-enabled pooling. Both were fixed, given regressions and independently re-reviewed with no remaining findings. The async-cancel-safety rereview found zero missing annotations or cancellation bugs in the changed scope. The focused Clippy command above supplies compiler evidence for that source review.

F1 red was the actual mapper assertion from the preceding audit; its mapper was unchanged at this job's base. F3 red was repeated on the current service source. F4 red reproduced entry failure reported as exit failure. F2 used the preceding live-carrier shutdown reproduction and the unchanged no-op shutdown body. Fresh F2/F5 tests first ran after the fixes; no fresh red is claimed for them.

Two Gradle attempts stopped before tests: full disk prevented toolchain extraction, then Google Maven returned No route to host. Existing installed JDK 17 and copied cached dependencies allowed offline execution. A later full static-analysis run hit full disk in Hilt generation; only this job's disposable build outputs were removed before the successful repeat. No foreign worktree, branch, source or cache was deleted.

## Fix commits

- F1: 756eecb375; equivalent fallback simplification for the method budget: daf54b5864.
- F3: 7c618e5ba9.
- F5: 2424591243.
- F4: 8b0acd2f5a.
- F2: 98e2d19b9e.

RLY-1794335433511524 owns the combined review and gate evidence above. No physical-device, external-server, hosted-CI, release-artifact or deployment acceptance is inferred from these host checks.

## Rebase checkpoint

The branch was rebased without conflicts onto origin/main at 88b64db372fed0d0f2f0ff179d1a0f6b5b557edf. The 15 RLY-16 source and test files match the validated source hashes. Upstream changed service code and a Rust dependency, so the affected gates must run again. The first repeat stopped before compilation with No space left on device. The subsequent repeat passed all five gates: Kotlin, staticAnalysis, Rust tests, Clippy and ci-gates. No foreign build directories were deleted. Main integration and push follow this acceptance record.

The successful rebased commands are recorded in `/tmp/rly16-verify/rebased-results.json` with exit code 0 for all five steps. They use the commands above. Host Cargo debug information was disabled with CARGO_PROFILE_DEV_DEBUG=0 and CARGO_PROFILE_TEST_DEBUG=0 to bound disk use; test assertions remain enabled. After Kotlin and staticAnalysis passed, only this job isolated Gradle cache and old Rust metadata were removed. Rebased source and test inputs are unchanged from code commit daf54b58643f6b3ecf5efaf1a3336769172e5aed.
