---
task_id: TST-1792776319490914
change: android-transport-acceptance
commit_sha: null
local: required
local_evidence: null
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
| REQ-ANDROID-P1-TLS | TST-1792776452973210 | Real nginx TLS peer and complete Android positive/negative tests | required |
| REQ-ANDROID-TRANSACTION | TST-1792776459525304 | Four app VPN transports with payload digests and destination receipts | required |
| REQ-ANDROID-RECOVERY | TST-1792776460035152 | Routed UDP failure, applied TCP reserve and completed traffic | required |
| REQ-ANDROID-MIGRATION | TST-1792776460563922 | Replacement receipt before old peer retirement and negative replacement | required |
| REQ-ANDROID-USB-FLOW | TST-1792776459525304 | Real concurrent flow and delayed reply tests, malformed/bound/cleanup tests, final Android Hysteria acceptance | required |
| REQ-ANDROID-EVIDENCE | TST-1792776461086297 | Python/Go/Kotlin gates, verified artifacts, owned physical device, sleep/wake and cleanup | required |

## Intermediate integration checkpoint

The task remains in progress. Device execution and hosted CI are pending.

Observed checks on the reviewed harness source:

- Six Python harness test modules: 60 tests passed.
- Test-lab execution tests: 39 tests passed.
- The actual nginx TLS XHTTP peer passed a 256 KiB transfer, wrong UUID,
  untrusted certificate, wrong server name, peer loss, and recovery checks.
- The three-peer migration runner test passed with separate UUIDs, ports,
  and replacement bundle content.
- The Hysteria2 fixture passed real encrypted UDP transfer, peer loss,
  and recovery checks on the host. This is not Android acceptance.
- Kotlin formatting, workflow lint, manifest generation, and task validation passed.
- The first ARM64 app and instrumentation APK preparation passed. Gradle
  completed 400 tasks in 21 minutes 20 seconds. The committed source will be
  prepared again before device execution.

The authorized physical device uses the separate local USB scenarios. These
scenarios verify production full-TUN traffic and native protocol exchanges
through a test-only USB carrier. Physical sleep/wake is required and pending.
Direct device-to-host UDP, underlying-network bypass prevention, Wi-Fi/cellular
handover, filtered networks, and public-provider acceptance remain unverified.

## USB flow correction

The intermediate Hysteria run at `e53962825c83986a129dd4338256b47f5a7ced90`
confirmed fresh positive-pin startup and authentication, then failed its UDP DNS
check. The old carrier sent all replies to the most recent Android UDP source.
Concurrent QUIC sessions therefore received each other's packets, matching the
observed invalid connection ID drops. This is a test transport contract defect;
production certificate and payload assertions remain unchanged. Final acceptance
is still required after the versioned per-flow carrier change.

The USB Hysteria scenario includes a bounded Android loopback check of the actual
TestUdpBridge implementation before protocol traffic. Two real DatagramSocket
sources remain outstanding, replies return in reverse order, and an unknown reply
flow must close the bridge and its workers. Source compilation is separate from
the required device execution. AWG acceptance queries the configured IPv4 and IPv6
DNS peers from the separate UID and requires new matching A and AAAA peer receipts;
the seeded LAN routing policy remains unchanged.

Observed local checks for the USB flow correction:

- Six Python harness modules: 78 tests passed, including 16 bridge tests.
- Android instrumentation Kotlin compilation, app ktlint, and app detekt passed.
- Architecture health: no new or worsened indicators.
- Task contracts: 157 tasks and 488 steps validated.
- Independent review approved both bridge endpoints, host tests, Android loopback
  checks, and dual-stack AWG DNS assertions. No confirmed defect remains.

The Android loopback and IPv6 DNS checks still require physical execution on the
final combined candidate. These local results do not close the acceptance task.
