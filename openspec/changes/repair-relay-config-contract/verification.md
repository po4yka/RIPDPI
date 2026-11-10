---
task_id: RLY-1794335295387247
change: repair-relay-config-contract
commit_sha: 763f7bb44910571d82877a97f470f48fd8d18c9f
local: passed
local_evidence: Post-rebase 99 JVM tests and 342 Rust tests passed; live decoder check accepted 17 current payloads and rejected 51 schemas; the 1344-case Kotlin-policy/native-builder matrix had zero mismatches; staticAnalysis, ci-gates, architecture health, locked metadata, affected Clippy, formatting, 38 CI contract tests and actionlint passed. Independent Kotlin, Rust, JNI, async and CI reviews found no open findings.
remote_ci: required
remote_ci_evidence: The authorized main push will trigger CI. Hosted execution is not claimed by local validation; retain review state until remote evidence is resolved.
device: not_applicable
device_evidence: Host tests cover the changed pure validation and Room transaction logic. Physical-device, root and external-server acceptance were not authorized or run.
artifact: not_applicable
artifact_evidence: No artifact pin, JNI signature or packaging contract changes.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

The tested source is based on main `fb2e7e6a320bbbbaef05c00ac82ff18559786a3a`.
The following evidence update changes task records only.

## Requirement evidence

| Requirement | Execution step | Observed evidence | Result |
|---|---|---|---|
| REQ-RLY-1794335295387247-001 | RLY-1794335371231289 | RelayProfileActivatorTest uses the production Room coordinator: Native selection, explicit selection preservation and rollback | passed |
| REQ-RLY-1794335295387247-002 | RLY-1794335371925105 | PasswordRelayProfileValidationTest, PasswordRelayImportAdmissionTest and SubscriptionReserveRefreshTest reject invalid ports before mutation; refresh RED returned Updated instead of PARSE_ERROR, then GREEN preserved members, failover and timestamps | passed |
| REQ-RLY-1794335295387247-003 | RLY-1794335372761763 | Actual create_session tests reject missing, retired, future, malformed and duplicate envelopes; current Standard and Apps Script paths accept; standalone aliases remain supported | passed |
| REQ-RLY-1794335295387247-004 | RLY-1794335373340330 | Actual backend and runtime tests reject unknown transport before readiness; regenerated Kotlin production-policy matrix compares 1344 actual builders with zero mismatches | passed |
| REQ-RLY-1794335295387247-005 | RLY-1794335373929312 | Registry guard rejects incomplete rows; live Kotlin serializer bytes reach actual Standard and Apps Script decoders; 17 current cases accept and 51 schema cases reject; required CI job and aggregate failure behavior are checked | passed |
| REQ-RLY-1794335295387247-006 | RLY-1794335373929312 | Deterministic real Room race was RED with an old endpoint and new password; recovered-read scope is GREEN and the waiting mutation completes | passed |
| REQ-RLY-1794335295387247-007 | RLY-1794335373929312 | Planned reuse is compared with built Mieru and Reality factories for mux submodes; the original Mieru-off mismatch was RED | passed |

## Commands and evidence

Compiler-backed commands ran through `build-gate --lane ripdpi`, with Cargo jobs 2 and Gradle workers at most 4. Cargo resolution used `--locked`.

- The exact combined command transcript is in `/tmp/rly19-rebased-gates.sh`.
- Kotlin filters cover runtime-state password/native validation, real Room activation and import, subscription refresh, coherent reads, registry drift, provider resolvers and selector reload. XML totals: runtime-state 16, service 56, app 27; no failures, errors or skips.
- `cargo test --locked -p ripdpi-relay-core -p ripdpi-relay-android -p ripdpi-apps-script-core --lib --jobs 2 --no-fail-fast -- --test-threads=1`: 252 + 16 + 74 passed. Two optional peer tests and the separately executed live-export test are ignored by this generic command.
- `cargo clippy --locked -p ripdpi-relay-core -p ripdpi-relay-android -p ripdpi-apps-script-core --all-targets --jobs 2 -- -D warnings` and `cargo fmt --all --check`: passed.
- `just test-relay-config-contract`: producer test passed; exact native test passed, zero ignored, 17 current payloads accepted and 51 invalid schemas rejected. This proves decoding, not backend activation or external wire compatibility.
- `./gradlew staticAnalysis --max-workers=4 -Pripdpi.skipNativeBuild=true -Pripdpi.requireLibXray=false --no-build-cache`: passed.
- `just ci-gates`, `python3 scripts/ci/check_architecture_health.py`, and `cargo metadata --locked --format-version 1` from native/rust: passed. No baseline, API snapshot, dependency or schema version was changed by this repair.
- `python3 -m unittest scripts.tests.test_resolve_change_routing scripts.tests.test_cross_language_runtime_contracts`: 38 passed; `actionlint .github/workflows/ci.yml`: passed.

The full 1344-case check ran in a new temporary snapshot. The actual Kotlin policy and serializer generated 120 accepted and 1224 rejected cases. An observational test invoked the actual native builder. The wrapper required zero mismatches and one executed test; all conditions passed. Corpus, test, lockfile and version catalog hashes stayed unchanged. The first temporary-copy attempt failed before native assertions because its file filter omitted three tracked vendored build-source files; those exact Git blobs were restored and the native check passed. Production sources were not changed for this check.

Local logs and commands are preserved in `/Users/npochaev/Downloads/RIPDPI_network_audits/RLY-19-fixes-evidence-20261111-001744`.

## Scope decisions and residual checks

- H1 is an intentional process-wide quality aggregate; document it and keep current consumer behavior.
- Chain-hop mux DTO fields remain default-only reserved fields. Non-default native chain mux stays rejected.
- These checks add C and V evidence. They do not add S or E evidence. The original audit records the checked specifications and its independent upstream peer result.
- Android import-to-running-VPN acceptance, process death on Android, physical-device probes, root activation and external-server interoperability were not run by this repair.
- Hosted CI must execute the new required relay-config-contract job. Local execution does not establish its cold-run duration on Ubuntu.
