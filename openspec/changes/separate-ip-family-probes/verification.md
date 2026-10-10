---
task_id: DGN-1791529750911166
change: separate-ip-family-probes
commit_sha: null
local: passed
local_evidence: 717 Rust tests, 1623 diagnostics tests, seven app tests, full-workspace Clippy, staticAnalysis, app/service locale lint, ARM64 native build, architecture, contract, API snapshot checks and independent review passed.
remote_ci: required
remote_ci_evidence: null
device: blocked
device_evidence: adb devices -l returned no connected devices; real IPv6-only and NAT64 paths remain unverified.
artifact: blocked
artifact_evidence: verifyLibXrayArtifacts failed because native/xray/artifacts is missing; APK packaging cannot pass without the existing producer artifact.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-IP-SEPARATE | DGN-1791529941427129 | Combined Rust suite: 717 passed, 8 pre-existing ignored | Passed |
| REQ-IP-NAT64 | DGN-1791529941427129 | Combined Rust suite and protocol regression tests | Passed |
| REQ-IP-PRESENT | DGN-1791529942331256 | Full diagnostics module: 1623 passed | Passed |
| REQ-IP-PRESENT | DGN-1791529943213754 | Seven app tests, two inspected renders and app/service locale lint | Passed |
| REQ-IP-BOUNDS | DGN-1791529944060203 | Combined tests, staticAnalysis, Clippy, ARM64 build, architecture and independent review | Passed |

## Behavior and scope

The optional `ipFamilyProbe` request produces three independent rows. Each row owns a numeric-family TCP control test; no hostname resolution or alternate family can satisfy it. NAT64 first discovers a prefix through the captured network DNS servers, then connects to a synthesized control address. Discovery alone is inconclusive for translation reachability. The checks do not prove HTTP, TLS, full Internet access, provider interference, or physical IPv4 without CLAT.

The profile is manual, raw-path only and excluded from background and quick scans. Full analysis adds one dedicated stage. Current results, history, connection stages and summaries retain separate facts. Network changes revoke positive authority. Redacted archives remove destinations, prefixes and unknown fields from nested evidence.

## Review and initial evidence

Independent review found no remaining critical or warning findings. Regressions cover IPv4-mapped addresses, repeated discovery sentinels, response order, multiple prefixes, cancellation and deadlines before TCP, and consistency between prefix and destination. Native Clippy passed for the six affected crates and all targets.

The UI worker observed seven passing tests, app formatting and detekt. The saved-record mapping path is covered. English and Arabic RTL renders at 360 dp, with Arabic font scale 1.5, were inspected for clipping and wrapping. All 42 resource keys exist in all 10 locales.

`adb devices -l` returned no connected devices. Device and real-network IPv6-only/NAT64 acceptance remain unverified.

## Combined tests

`cargo test --locked -p ripdpi-diagnostics-contracts -p ripdpi-diagnostics-dns -p ripdpi-diagnostics-transport -p ripdpi-diagnostics-runner -p ripdpi-diagnostics-probes -p ripdpi-monitor-engine -- --test-threads=1` passed: 717 tests and eight existing ignored external-network, timing-sensitive or opt-in soak tests. No tests were disabled for this change.

`./gradlew :core:diagnostics:testDebugUnitTest :app:testGithubFullDebugUnitTest --tests '*DiagnosticsIpFamily*' -Pripdpi.skipNativeBuild=true --max-workers=2` passed: 1623 diagnostics tests and seven app tests. Initial failures in six Home tests were stale stage counts/order/session IDs; explicit expectations now include the new stage and preserve failure/cancellation assertions. JVM tests use the documented native-build opt-out; Android native compilation is recorded separately.

The two shared fixture updates were generated and reviewed under the user's prior approval. The owning catalog check, 18 taxonomy tests and five governance tests passed. Contracts and transport API snapshots were generated from locked nightly rustdoc with cargo-public-api 0.52.0 and the owning snapshot normalizer. The transport snapshot also reconciles exports already present in the base source.

Architecture health reports zero new or worsened indicators. Native architecture reports zero violations. Locked Cargo metadata, strict OpenSpec validation and diff checks passed.

`staticAnalysis`, `:app:lintGithubFullDebug`, `:core:service:lintDebug` and the seven app tests passed together. Full-workspace Clippy with `--locked --workspace --no-deps --all-targets -- -D warnings` passed. Updated English and Arabic RTL renders were inspected after the DNS/TCP attempt label change.

## Android build and remaining acceptance

`./gradlew :core:engine:buildRustNativeLibs --max-workers=2 --offline` passed for the default local `arm64-v8a` ABI in 3m 56s. This includes the changed monitor and Android bridge dependencies. `./gradlew :core:engine:verifyLibXrayArtifacts --max-workers=2` failed because `native/xray/artifacts` does not exist. This required producer artifact blocks APK packaging; no APK or device acceptance is claimed.

Remote CI, connected-device and real-network NAT64 acceptance remain separate from these local checks. The task stays in review until their evidence is available.
