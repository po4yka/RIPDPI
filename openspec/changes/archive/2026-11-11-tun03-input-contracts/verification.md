---
task_id: TRN-1794384012167321
change: tun03-input-contracts
commit_sha: 1f90e28198ab0811289b026007ce3f5eaa31c293
local: passed
local_evidence: 139 selected JVM tests, affected lint, full staticAnalysis, ci-gates, architecture health, full locked Cargo metadata, task validation, and independent review passed.
remote_ci: not_applicable
remote_ci_evidence: This repair uses repository local acceptance; hosted CI is not a required acceptance category and is not claimed as passed.
device: not_applicable
device_evidence: Kotlin input and persistence boundaries are covered by JVM and Room tests; no native transport, JNI signature, route, or UI layout change is planned. Physical and external transport acceptance remains outside this repair.
artifact: not_applicable
artifact_evidence: No APK or native artifact production is owned by this source repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-INPUT-IPV6 | TRN-1794384205371297 | URI codec and 34 editor tests preserve bare IPv6 through import and activation | passed |
| REQ-AWG-INPUT-HPKEY | TRN-1794384209766294 | Three model tests check canonical Base64, padding bits, lengths, zero and unset keys | passed |
| REQ-AWG-INPUT-QUERY | TRN-1794384211583615 | 17 URI tests include malformed tokens, duplicate keys and valid values | passed |
| REQ-AWG-STORED-WIRE | TRN-1794384214181311 | 14 real Room tests cover strict nested wire fields and preserve rows and synthetic secrets | passed |
| REQ-AWG-INPUT-CARRIER | TRN-1794384216356554 | 15 activation and 18 resolver tests reject unknown carriers and retain UDP and WS | passed |

## Acceptance scope

Each of the five findings has a separate fix commit. A further reviewed refactor meets the URI parser return-count limit. The source SHA above includes all code changes. A push does not establish hosted CI, Android runtime, or production server compatibility.

## Observed local checks

The final checks ran on the exact source SHA above. The owner allowed lane `rly14` for this task because `ripdpi` was occupied. Gradle used a private user home, JDK 21 plus the local JDK 17 toolchain, a 5 GiB Gradle heap, a 3 GiB Kotlin heap, and at most four workers. Native builds were disabled for JVM and lint tasks; no APK or native artifact is claimed.

- Selected suites in model, runtime-state, data, service, and app: 139 tests, zero failures, errors, or skips. The result includes URI, configuration, key, profile, Room, resolver, and editor coverage.
- Affected `:core:data:runtime-state:detekt` and `ktlintCheck`: passed.
- Full `staticAnalysis --continue`: passed, including GithubFull and GithubSimple lint.
- `just ci-gates`: passed. Rust API snapshots match on Darwin; the Linux-owned runtime-platform snapshot is skipped by the gate policy. No snapshot or baseline changed.
- `python3 scripts/ci/check_architecture_health.py`: zero new, worsened, or stale indicators; 18 existing baseline indicators.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1`: passed with Rust 1.98.1 and a private target directory.
- `./taskctl validate`: passed with 197 tasks and 667 steps before task closure.
- Independent read-only review: no open findings. A focused follow-up also approved the return-count refactor.

The exact command script, test XML summary, and logs are stored locally at `/tmp/ripdpi-tun03-fixes-DiM2u1/`. The audit report and its fix supplement are under the owner's Downloads audit directory.

## Evidence boundary

These repairs add observed host JVM evidence for input and persistence contracts. They do not add Android/JNI runtime or external peer evidence. Rust wire, crypto, cookie, replay, and rekey code did not change. The prior audit's pinned-reference and loopback evidence keeps its original scope; it was not rerun for this Kotlin repair. Physical and external transport checks and the MTU and AAAA-only DNS hypotheses remain separate.
