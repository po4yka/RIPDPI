---
task_id: VPN-1794152738556618
change: vpn08-flow-owner-attribution
commit_sha: de72951250984696142597abf7f3fd6c68db9cd6
local: passed
local_evidence: Eleven owning crates passed 1354 tests with 14 existing ignored cases; final changed core passed 502 unit and 18 integration tests. All-target Clippy, staticAnalysis, ci-gates, architecture health and locked metadata passed. Service 2109 and engine 309 unit tests passed without failures, errors or skips.
remote_ci: required
remote_ci_evidence: Read exact pushed SHA and workflow results after main integration; no remote result is claimed yet.
device: blocked
device_evidence: No physical two-UID, root, handover, package broadcast or release ART/R8 acceptance was run. Physical active probes and root activation require separate authorization. Host and native build evidence does not prove Android kernel ownership.
artifact: passed
artifact_evidence: Standard core-engine buildRustNativeLibs passed in 5m43s without skipNativeBuild at this source SHA. Five arm64-v8a libraries use pinned NDK 29.0.14206865 and android-jni profile; every ELF LOAD alignment is 0x4000. APK packaging, other ABIs and device execution were not checked.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN08-UDP-AGE | VPN-1794152941836461 | F1 bca3a756d: genuine stale-generation RED after 5 s, then 18 owning tests passed; final owning suite has 21 tests. TTL is absolute and traffic does not renew it. Reuse within 5 s remains a known limit. | Passed (host) |
| REQ-VPN08-START | VPN-1794152942868249 | F4 d6a776445: first-flow and registration-error RED; 59 targeted engine tests passed, including cancellation rollback. Final engine suite: 309 passed. | Passed (host) |
| REQ-VPN08-SESSION | VPN-1794152943919544 | F3 0bc4b154e: stale-counter RED, then 42 targeted tests passed. Final service suite: 2109 passed. Session and package revision fence commits after external lookups. | Passed (host) |
| REQ-VPN08-DESTINATION | VPN-1794152944925858 | F2 6d2be816e: learning RED 3 failed/1 passed, then 92 passed; authority and same-package/different-UID regressions passed. Real-loopback proxy suite: 380 passed. Scope follows the original owner through transport and learning; no destination IP identity join remains. | Passed (host) |
| REQ-VPN08-UNKNOWN | VPN-1794152944925858 | Slow/unknown owner uses bounded 100 ms soft grace; armed admission stays closed. F8 f20e37d10: two genuine RED cases for oversized reassembly and full queue; three isolated real-wire cases passed, including unchanged armed rejection. Final core: 502 unit and 18 integration passed. | Passed (host) |
| REQ-VPN08-PRIVACY | VPN-1794152944925858 | Opaque session-local UUID uses private UID/package/version lookup, redacted object strings and bounded cache. Shared UID packages do not produce guessed app scope. RFC1929 literal frames and optional telemetry field passed. Scope is carried only by authenticated loopback transport. Device logcat/export inspection was not run. | Passed (host); device unverified |
| REQ-VPN08-MAPDNS | VPN-1794152945958197 | F5 23e726f00: actual smoltcp TCP handshake and production UDP route/retry reach literal SOCKS auth with the original scope and independently expected real target. F6 a84806f8e: mapping pin handoff, reset, overflow and fast-Allow RED/GREEN; stale unleased mapping still fails after underlay change. | Passed (host) |
| REQ-VPN08-RAW | VPN-1794152944925858 | F6 a84806f8e: actual Lua VM Drop and non-root STUN policy DROP retire old response context before return. Exact-generation and neighbor cases passed. Real UDP/TCP peer oracle checks same-owner port and control connection reuse. No raw socket, root helper or device root activation ran. | Passed (host); privileged execution unverified |

## Review and combined gates

Correctness, JNI, async and final integration reviewers passed. F8 received separate correctness and async review. No confirmed review blockers remain. No baseline or golden fixture was changed.

The full eleven-crate run passed 1354 cases with 14 pre-existing ignored cases. F8 changes only tunnel-core; its complete Rust subtree matches the tested candidate (tree 3637abc08a56a11d4a3923415810780610fd0eed). Final core passed 502 unit plus 18 integration cases, all-target async-lock Clippy and complete ci-gates. Final root ci-gates also passed after the two Kotlin style changes. These results are not load, interoperability or device acceptance.

The initial combined run found a Darwin forced-drain test failure. An independent loopback oracle distinguished FIN/EOF from abortive RST. Test-only 97fb903d0 sets the timeout before shutdown and accepts only EOF or ConnectionReset; it still rejects timeout, invalid socket and nonempty data. The narrow test and full eleven-crate rerun passed. Production shutdown code did not change.

Final commands and outputs are captured in the external audit supplement:
`/Users/npochaev/Downloads/RIPDPI_network_audits/VPN-08-fixes-2026-11-08.md`.
Primary local logs: `/tmp/vpn08-final-owning-tests.log`, `/tmp/vpn08-final-owning-clippy.log`, `/tmp/vpn08-final-kotlin-complete.log`, `/tmp/vpn08-final-complete-ci-gates.log`, `/tmp/vpn08-final-android-native.log`. Artifact SHA-256 values and paths: `/tmp/vpn08-final-native-artifacts.json`.

## Integration base

The job was rebased without conflict onto a49ced806b43f381d28e7c3bf868d2df3c26e872. Incoming changes affect app instrumentation tests and other task verification records. Native Rust, core/service, core/engine and their build and gate inputs are byte-identical to the tested source SHA above. The native tree remains 3637abc08a56a11d4a3923415810780610fd0eed. No Android instrumentation execution is claimed.

## Remaining acceptance

Use two controlled non-root Android UIDs and an independent kernel/peer oracle for exact tuple reuse inside and after the 5 s age limit, connected and wildcard UDP, IPv4/IPv6, unknown owners and excluded UID admission. Run stop/revoke/restart, handover and package replacement during lookup. Run a release ART/R8 callback and privacy export smoke. Physical active probes and root activation require separate explicit authorization. No such result is inferred from a Kotlin fake, host loopback or native compile.

Keep the task in review. Do not archive or close it while required remote and device evidence is missing.
