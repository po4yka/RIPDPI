---
task_id: RTE-1792840535644306
change: awg-profile-dns-routing
commit_sha: null
local: passed
local_evidence: Full engine and service JVM suites, four-module Kotlin static gates, three native crate suites and all-target Clippy passed on the reviewed working tree based on f369f172b3e869bb6805c3224e95e49152ffc3c4.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-DNS-SCOPE | RTE-1792840610583608 | Kotlin runtime projection and native resolver port matrix passed | Passed locally |
| REQ-AWG-DNS-POLICY | RTE-1792840611038760 | Native ordered Block/Direct and TCP/UDP upstream policy matrix passed | Passed locally |
| REQ-AWG-DNS-AUTHORITY | RTE-1792840610583608 | UI/JSON/session codec, mutation isolation, invalid authority, inactive remembered input and profile-switch checks passed | Passed locally |
| REQ-AWG-DNS-ACCEPTANCE | RTE-1792840611484317 | Rebuilt integrated Android artifact and physical peer receipts pending | Required |

## Observed defect

Physical candidate f369f172b3e869bb6805c3224e95e49152ffc3c4 completed the AWG handshake but failed the explicit profile DNS query. Source inspection confirms that the seeded Direct LAN CIDRs match both private resolver addresses and that Direct removes the AWG upstream. This was the physical reproduction before the fix; no passing post-fix device result is claimed.

## Local verification

All checks below ran on the reviewed working tree based on
`f369f172b3e869bb6805c3224e95e49152ffc3c4`. The integration owner must record the
final source SHA and required hosted/device/artifact evidence before closure.

- `build-gate -- cargo test --manifest-path native/rust/Cargo.toml --locked -p ripdpi-proxy-config -p ripdpi-config -p ripdpi-proxy-runtime`: exit 0; 645 passed, zero failed or filtered; 13 existing opt-in live/load/soak tests ignored.
- `build-gate -- ./gradlew :core:engine:testDebugUnitTest :core:service:testDebugUnitTest -Pripdpi.skipNativeBuild=true --max-workers=4`, together with all four affected modules' `ktlintCheck` and `detekt`: exit 0; engine 326 tests and service 2297 tests, zero failures, errors or skips.
- `scripts/ci/cargo-guarded.sh cargo clippy --locked -p ripdpi-proxy-config -p ripdpi-config -p ripdpi-proxy-runtime --all-targets -- -D warnings`: exit 0 through the machine-wide build gate.
- `cargo fmt --manifest-path native/rust/Cargo.toml --all`, native architecture contracts, full architecture health, task contracts, and strict OpenSpec validation passed. Architecture health reported 21 existing indicators, zero new or worsened.
- Independent source and test review approved the exact-IP/port scope, frozen request authority, ordered Block preservation, and empty-field omission.

The ordinary round-trip test rewrote three existing fixture files only by moving
`warp.amnezia.wireProfile` to its serializer declaration order. All three JSON
objects remained semantically equal, and `awgDnsServers` was absent. These
incidental generated changes were removed; no golden fixture or baseline changed.
The initial compile/static failures were repaired in test setup and formatting;
no check was suppressed. No separate pre-fix JVM/Rust failure is claimed.
