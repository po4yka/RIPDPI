---
task_id: SVC-1792856195708453
change: physical-network-handover-authority
commit_sha: 47a5ee708a497086eb29739ca17f35e164119846
local: passed
local_evidence: Four complete JVM suites passed 6288 tests without failures, errors or skips. AndroidTest Kotlin and Java compilation passed. One detekt ReturnCount finding was corrected; the final 26-case selection suite and service detekt, ktlint and lint passed. Independent review approved all source changes. Integrated behavior paths match reviewed commit ff134c2a5261cbb04850cd571dc989b706989966.
remote_ci: required
remote_ci_evidence: Final published candidate hosted gates remain required. Earlier source 38e64afcf9f097a60c8dd9146ed431c108fe35ef passed 56 checks with 19 planned skips; its Linux io_uring job 120911596401 passed 254 tests with no skips, positive WRITE_FIXED completions and zero fallback.
device: passed
device_evidence: All four local USB scenarios passed on source 47a5ee708a497086eb29739ca17f35e164119846 with the same genuine APK pair and complete owned cleanup. P0/P1 passed in 164.863 seconds, Hysteria in 161.927 seconds, AWG in 81.898 seconds and migration in 15.205 seconds. External paths, direct device UDP, bypass prevention and Wi-Fi/cellular handover remain unverified. Stable physical sleep/wake passed with real payloads and automatic reserve/recovery. Earlier source 38e64afc failures retain unproven physical-network versus VPN-overlay attribution.
artifact: passed
artifact_evidence: Genuine arm64-v8a preparation passed at clean source 47a5ee708a497086eb29739ca17f35e164119846 in 2 minutes 3 seconds, with native compilation enabled and libXray verified. Independently verified preserved app SHA-256 f41bd47c48edb357f10d7f396399be6a997bc7151e9e69af8a31930079a77eef and test SHA-256 43a7587e955638efc870fcd9cb612fac9affe0cabffd0ad4d5a4616dcf15af2b.
deployment: not_applicable
deployment_evidence: No provider, credential, host or deployment action is included.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PHYSICAL-AUTHORITY | SVC-1792856270513687 | Selected physical source and one observer with matching fingerprint and MTU | Passed |
| REQ-PHYSICAL-CHANGES | SVC-1792856270934423 | Overlay, true transport, DNS, private-DNS, route, MTU and unrelated callback regressions | Passed |
| REQ-PHYSICAL-UNAVAILABLE | SVC-1792856270934423 | Incomplete, blocked, lost, restored and legacy ambiguity tests | Passed |
| REQ-PHYSICAL-PRIVACY | SVC-1792856270934423 | Unchanged scope hashes and serialization with exact binder-issued DNS authority | Passed |
| REQ-PHYSICAL-ACCEPTANCE | SVC-1792856271355520 | Complete affected suites, reviewed source, genuine artifact, four USB scenarios and hosted gates | Required |

## Source regression evidence

Two initial behavioral regressions failed before the correction: a stable
physical Wi-Fi path was reported as VPN, and a 1500-byte physical MTU yielded
1340 instead of 1420 after reading the already reduced VPN MTU. Both passed
after all affected consumers used one selected physical snapshot.

The processor regression also failed before the narrow cooldown correction:
the second physical change within ten seconds was suppressed. All 14 processor
cases passed after cooldown compared both scope hash and applied transient
physical signature. Failed or stale attempts do not store successful state.

## Local gates

- Complete model, data, service and app GithubFull suites passed 58, 959, 2268
  and 3003 tests respectively. Each suite had zero failures, errors or skips.
- GithubFull AndroidTest Kotlin and Java compilation passed.
- The combined staticAnalysis command exited 1 solely for ReturnCount in
  selectedSnapshot. Its equivalent nullable-expression correction passed the
  final 26 selected-physical tests and service detekt, ktlint and lint. All
  other static tasks completed successfully in the combined run.
- Independent source review approved the snapshot, lease, cooldown and rename
  changes, then the exact return-expression correction. Normal author commit
  hooks passed. No baseline or suppression was added.
- Integration path comparison, Cargo metadata and format checks passed.
  Architecture remained at 21 baseline indicators with zero new, worsened or
  stale indicators. Task and strict OpenSpec validation passed.

Genuine artifact preparation and all four owned physical USB scenarios passed.
Hosted gates on the final published candidate remain required. Earlier device
failures retain their original scope and unproven physical-network attribution.

## Final physical candidate 47a5ee70

All four local USB scenarios passed on source 47a5ee708a497086eb29739ca17f35e164119846 with the same genuine APK pair and complete owned cleanup. P0/P1 passed in 164.863 seconds, Hysteria in 161.927 seconds, AWG in 81.898 seconds and migration in 15.205 seconds. External paths, direct device UDP, bypass prevention and Wi-Fi/cellular handover remain unverified. Stable physical sleep/wake passed with real payloads and automatic reserve/recovery. Earlier source 38e64afc failures retain unproven physical-network versus VPN-overlay attribution.

Genuine arm64-v8a preparation passed at clean source 47a5ee708a497086eb29739ca17f35e164119846 in 2 minutes 3 seconds, with native compilation enabled and libXray verified. Independently verified preserved app SHA-256 f41bd47c48edb357f10d7f396399be6a997bc7151e9e69af8a31930079a77eef and test SHA-256 43a7587e955638efc870fcd9cb612fac9affe0cabffd0ad4d5a4616dcf15af2b.
