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

## Fixture corrections after candidate 818eeeba

Candidate `818eeeba8569c0a4cc01d3486b81a0f1c643d55c` passed genuine native
APK preparation and the physical Android bridge self-check. The subsequent
Hysteria DNS assertion failed with 17 observed carrier flows. An independent
real-socket reproduction showed that the fixture gate's old 16-source limit
stopped forwarding for both the new source and existing sources. Cleanup closed
the sockets and then propagated the worker error. The failed run did not retain
that inner error, so its exact attribution remains supported by the reproduction.

The gate now shares the carrier's 64-flow limit. Real-socket regressions cover all
64 flows, reuse of the first flow after clients 17 and 64, refusal of client 65,
resource closure, retained error causes, and redacted failure receipts. All 51
fixture, bridge and runner tests passed. This does not establish a new physical
Hysteria result.

The AWG run reached the peer DNS through the tunnel but failed before its explicit
probe because valid background Android questions incremented the fixture error
count. The closed resolver now returns REFUSED for other names and records only
a separate rejection count. Owned A/AAAA answers and receipts remain unchanged;
malformed or unsupported owned queries remain errors. The new real-UDP regression
failed before the change and passed after it. The complete AWG peer Go suite passed
with the race detector, including error-counter and receipt-isolation checks.
Physical IPv4 and IPv6 DNS acceptance still requires a new run.

The same candidate passed physical migration with cleanup: independent mirror
refresh, a replacement readiness receipt and a separate-UID 1 MiB replacement
transfer completed. Final combined acceptance and hosted checks remain open.

Candidate `ad4379c1672cfa62b5189aa62edaf8cbe47e0c5a` confirmed that background
DNS no longer increments AWG fixture errors. It then exposed a receipt endpoint
contract error: before the first owned query, the nil Go slice encoded as `null`.
The endpoint now always returns an array. The real UDP regression also queries
the real HTTP handler before traffic, after background refusals, and after owned
A/AAAA answers. It failed on the initial response before this correction.
The corrected complete AWG peer suite passed with the race detector.

The bounded failure collector passed 91 Python checks, including real HTTP
timeout, size, redaction, redirect and proxy tests. Its runner regression confirms
capture before peer teardown while preserving the failed instrumentation verdict.
Physical capture remains pending on the next integrated candidate.

Candidate `47f9c8f237d68fdc3db9348a6f65fe4be3470603` contains the automatic
selector acceptance path and the Hysteria DNS fixture correction. The selector
uses production probes, timers, selection and reload. Its separate explicit UDP
recovery phase does not count as automatic return to UDP. Service tests passed
(2300, no failures or skips), as did AndroidTest compilation, ktlint and Detekt.

The pinned Hysteria server lost the UDP ACL address rewrite. A direct-loopback
native control passed, while the synthetic rewrite failed; an IPv6 control
confirmed that packets still reached the original address. The fixture now uses
an exact UDP route through the existing Xray SOCKS ingress. The combined tree
passed 209 Python tests, the complete normal Go suite, and the new DNS ingress
race test. The native fixture driver executed one test with zero ignored tests:
two valid DNS responses and exact receipts, wrong pin and destination rejection,
and complete owned cleanup. Three other upstream tests were outside that run.

The complete Go race suite is not green. Unchanged baseline `ad4379c1` reproduces
both the upstream Vision checkptr failure in `vless/outbound.go:288` and the
XHTTP race between `WaitReadCloser.Set` and `Read` in `splithttp/client.go:195/205`.
The latter also appeared in the combined tree's new XHTTP quality-port race test.
No dependency or race-detector setting was changed. The ordinary full Go suite
passed; the new DNS ingress race test passed. These results do not establish
physical automatic failover, which remains pending on this candidate.

## Physical candidate 47f9c8f2 and observed blockers

All four physical scenarios used source
`47f9c8f237d68fdc3db9348a6f65fe4be3470603` and the same genuine APK pair:
app SHA-256 `3b461a2619799e186da3fcf7bb7f683dde4861b3e24f85cdec7db472fe5a959e`
and test SHA-256 `fb118446847ddf910d06e39326fc69d7eeed728d8d6c8edd89504ee47edd163e`.
Preparation passed, and all four runs completed owned cleanup.

- P0/P1 passed: REALITY and nginx TLS XHTTP each completed 1 MiB download,
  1 MiB upload, a 60-second stream and a 1 MiB recovery transfer. The peer
  recorded all eight transactions.
- Migration passed: the reserve peer recorded a 1 MiB transfer; the replacement
  peer recorded a 64 KiB readiness check and a separate complete 1 MiB transfer.
- Hysteria passed its Android carrier check, owned DNS query and 1 MiB download,
  then failed upload. The bounded collector retained DNS and download receipts
  after the failure and before cleanup.
  Automatic failover was not reached. A real-proxy reproduction identified the
  first-response observer blocking body forwarding with autolearn enabled.
  PRX-1792849175606125 owns that production correction and its acceptance.
- AWG instrumentation passed IPv4 and IPv6 DNS, download, upload, a 60-second
  stream, peer-loss rejection and recovery transfers. The scenario still failed
  at peer finish because its wire observer treated an H4 packet counter as H1.
  This is not a complete AWG acceptance result.

The AWG observer now checks pinned packet sizes before header offsets. Regression
tests reproduce counters 100 and 199 in each direction, preserve true H1 prelude
requirements, reject invalid packet sizes and exercise 512 transport counters
around two fresh handshakes. The complete AWG fixture Go suite passed with the
race detector. Independent review approved the correction. A new combined
physical run remains required; no production AWG behavior changed in this fix.
