---
task_id: SVC-1792830780372912
change: resolved-relay-proxy-projection
commit_sha: 47a5ee708a497086eb29739ca17f35e164119846
local: passed
local_evidence: Complete service JVM suite passed 2288 tests with zero failures, errors, or skips after the shared UDP policy commit; service ktlintCheck and detekt passed. The shared policy commit passed complete data, runtime-state, engine, and service suites plus all four module static gates. Architecture and task validation passed.
remote_ci: required
remote_ci_evidence: Final published candidate hosted gates remain required. Earlier source 38e64afcf9f097a60c8dd9146ed431c108fe35ef passed 56 checks with 19 planned skips; its Linux io_uring job 120911596401 passed 254 tests with no skips, positive WRITE_FIXED completions and zero fallback.
device: passed
device_evidence: All four local USB scenarios passed on source 47a5ee708a497086eb29739ca17f35e164119846 with the same genuine APK pair and complete owned cleanup. P0/P1 passed in 164.863 seconds, Hysteria in 161.927 seconds, AWG in 81.898 seconds and migration in 15.205 seconds. External paths, direct device UDP, bypass prevention and Wi-Fi/cellular handover remain unverified. Production startup used the ready relay; Hysteria DNS, download, upload, sleep/wake and automatic TCP reserve passed.
artifact: passed
artifact_evidence: Genuine arm64-v8a preparation passed at clean source 47a5ee708a497086eb29739ca17f35e164119846 in 2 minutes 3 seconds, with native compilation enabled and libXray verified. Independently verified preserved app SHA-256 f41bd47c48edb357f10d7f396399be6a997bc7151e9e69af8a31930079a77eef and test SHA-256 43a7587e955638efc870fcd9cb612fac9affe0cabffd0ad4d5a4616dcf15af2b.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RELAY-PROJECTION-IDENTITY | SVC-1792830889982340 | Normal startup and serialized envelope regression; complete 2288-test service suite | Passed locally |
| REQ-RELAY-PROJECTION-FAIL-CLOSED | SVC-1792830891083169 | Inverse UDP policy, invalid endpoint, renderer, selector, race, WARP/AWG, and cleanup tests in complete service suite | Passed locally |
| REQ-RELAY-PROJECTION-NORMALIZATION | SVC-1792834775339498 | Shared policy and engine suites passed; combined 2288-test service suite passed | Passed locally |
| REQ-RELAY-PROJECTION-IMPORTED-CAPABILITY | SVC-1792831043407677 | Imported mapping and independent review passed; physical migration acceptance remains required | Passed locally; device pending |

Independent source review passed. Exact integrated hosted, Hysteria device, and migration device evidence are owned by SVC-1792830892443639 and remain pending.

## Pre-fix evidence

The owned physical Hysteria run at fea412b9ef4165f3d4a97651ce7c0e9033de1d25 reached the native proxy envelope guard and failed with `relay transport hysteria2 is missing required capability UDP ASSOCIATE`, despite the saved relay profile enabling UDP. No payload traffic was observed. The device owner supplied this failure; it is separate from local JVM evidence.

The queued local JVM RED command was cancelled before Gradle started so implementation could proceed from the already observed physical failure. No JVM RED result is claimed. The complete affected JVM suite and static checks remain required after the fix.

## Local validation before engine normalization correction

The complete service JVM suite executed 2286 tests: 2283 passed, three failed, zero errors, and zero skipped. All three failures are in VpnServiceRuntimeCoordinatorTest and report the strict started-relay projection invariant. The engine UI normalizer clears valid Reality UDP and causes these failures. Existing coordinator assertions remain unchanged. AnyTLS, Trojan, and Shadowsocks have the same normalization loss and are included in the correction. This run is not a passing local gate.

SharedProxyRuntimeStackResolvedRelayTest passed all five normal-start regressions. UpstreamRelaySupervisorTest passed 43 tests and SubprocessRelayComponentsTest passed 16 tests. The same run completed core/service ktlintCheck and detekt. Architecture health reported 21 existing indicators, zero new indicators, and zero worsened entries. Root, security, and producer-compatibility source reviews approved the projection change; the engine correction needs its own review.

The combined source run below resolves this failed validation. Hosted checks and physical Hysteria and migration checks remain separate required gates.

## Combined source result

The shared policy change was committed as `4d57e71f6c16b1a02d93a8bc9c2e752364936061` and applied to the projection branch as `140c780dd`. Its complete JVM suites passed: data 987, runtime-state 311, engine 321, and service 2279; all 3898 tests had zero failures, errors, or skips. All four module ktlintCheck and detekt gates passed. Root and independent source reviews approved that change.

After combining that commit with the projection change, this exact command completed with exit code 0:

```text
build-gate -- env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :core:service:testDebugUnitTest :core:service:ktlintCheck :core:service:detekt -Pripdpi.skipNativeBuild=true --max-workers=4 --console=plain
```

The combined service suite passed 2288 tests with zero failures, errors, or skips. The three coordinator regressions from the previous run now pass with their original assertions. No test was filtered or disabled. No native code or capability guard changed. The source reviews cover the final projection conversion and endpoint producers. Hosted, artifact, physical Hysteria, and physical migration acceptance remain required after integration, so the portfolio task remains doing.

## Final physical candidate 47a5ee70

All four local USB scenarios passed on source 47a5ee708a497086eb29739ca17f35e164119846 with the same genuine APK pair and complete owned cleanup. P0/P1 passed in 164.863 seconds, Hysteria in 161.927 seconds, AWG in 81.898 seconds and migration in 15.205 seconds. External paths, direct device UDP, bypass prevention and Wi-Fi/cellular handover remain unverified. Production startup used the ready relay; Hysteria DNS, download, upload, sleep/wake and automatic TCP reserve passed.

Genuine arm64-v8a preparation passed at clean source 47a5ee708a497086eb29739ca17f35e164119846 in 2 minutes 3 seconds, with native compilation enabled and libXray verified. Independently verified preserved app SHA-256 f41bd47c48edb357f10d7f396399be6a997bc7151e9e69af8a31930079a77eef and test SHA-256 43a7587e955638efc870fcd9cb612fac9affe0cabffd0ad4d5a4616dcf15af2b.
