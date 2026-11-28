---
task_id: VPN-1795773987131888
change: vpn-1795773987131888-serve-the-vpn-tunnel-from-a-private-unix-proxy-listener
commit_sha: null
local: passed
local_evidence: Rust and Kotlin unit suites, clippy, and `just ci-gates` pass on the rebased branch; logs under the session scratchpad `unix-listener/`.
remote_ci: required
remote_ci_evidence: null
device: passed
device_evidence: Emulator API 35 runs of `NetworkPathE2ETest` and emulator API 27 and API 35 runs of `LocalSocksUnixRouteInstrumentedTest`. Three encrypted-DNS e2e tests fail on main too because the fixture serves DoH over http.
artifact: not_applicable
artifact_evidence: The change publishes no artifact of its own.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPL-NO-LOOPBACK | VPN-1795774217912211 | Emulator API 35, `NetworkPathE2ETest#vpnServiceOwnsNoLoopbackListenerWhileTcpAndUdpFlow`, run 5 | Passed: zero loopback listeners after TCP and UDP round trips; proxy logs `addr=unix` |
| REQ-VPL-PRIVATE-PATH | VPN-1795774216970611 | Emulator API 35, `NetworkPathE2ETest#vpnServiceOwnsNoLoopbackListenerWhileTcpAndUdpFlow`, run 5; `:core:service:testStandardDebugUnitTest` | Passed: one `px-` file while running, none after stop; sweep tests pass |
| REQ-VPL-TCP | VPN-1795774213020254 | `cargo test --locked -p ripdpi-tunnel-core`; Emulator API 35, `NetworkPathE2ETest#vpnServiceOwnsNoLoopbackListenerWhileTcpAndUdpFlow`, run 5 | Passed |
| REQ-VPL-UDP | VPN-1795774211939093 | `cargo test --locked -p ripdpi-proxy-runtime -p ripdpi-tunnel-core`; Emulator API 35, `NetworkPathE2ETest#vpnServiceOwnsNoLoopbackListenerWhileTcpAndUdpFlow`, run 5 | Passed; the remote-error test over the Unix socket is Linux-only and has not run locally |
| REQ-VPL-DNS-UNIX | VPN-1795862284401738 | `cargo test --locked -p ripdpi-dns-resolver -p ripdpi-tunnel-core -p ripdpi-ech-dns` | Passed; no device test resolves a name over DoH because the fixture serves DoH over http |
| REQ-VPL-AUTH | VPN-1795774210951595 | `cargo test --locked -p ripdpi-proxy-runtime` (`tests/unix_listener.rs`) | Passed |
| REQ-VPL-INAPP | VPN-1795774215813180 | `LocalSocksUnixRouteInstrumentedTest` on emulators API 27 and API 35; `:app:testGithubDebugUnitTest`; `cargo test --locked -p ripdpi-diagnostics-transport` | Passed: 7 of 7 on both API levels |
| REQ-VPL-COMPAT | VPN-1795774216970611 | `:core:service:testStandardDebugUnitTest`; emulator proxy-mode tests of `NetworkPathE2ETest` | Passed; the Xray provider has unit coverage only |
| REQ-VPL-CONTRACT | VPN-1795774213020254 | `cargo test --locked -p ripdpi-tunnel-android -p ripdpi-tunnel-config`; `:core:engine:testStandardDebugUnitTest` | Passed |
| REQ-VPL-FAILSTART | VPN-1795774210951595 | `cargo test --locked -p ripdpi-proxy-runtime`; `:core:service:testStandardDebugUnitTest` | Passed |
