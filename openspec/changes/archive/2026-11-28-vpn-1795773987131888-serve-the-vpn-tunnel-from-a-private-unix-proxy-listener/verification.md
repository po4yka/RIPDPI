---
task_id: VPN-1795773987131888
change: vpn-1795773987131888-serve-the-vpn-tunnel-from-a-private-unix-proxy-listener
commit_sha: 4437b3b978972801caaa80dbbaff37f218689954
local: passed
local_evidence: Rust and Kotlin unit suites, clippy, and `just ci-gates` pass on the rebased branch; logs under the session scratchpad `unix-listener/`.
remote_ci: passed
remote_ci_evidence: "CI run 46167726044 on main at 4437b3b978 concluded success: https://github.com/po4yka/RIPDPI/actions/runs/46167726044. Attempt 1 failed only in the host Go test of the Xray provider peer; the rerun of that job passed."
device: passed
device_evidence: Emulator API 35 runs of `NetworkPathE2ETest` and emulator API 27 and API 35 runs of `LocalSocksUnixRouteInstrumentedTest`. After the fixture began to serve DoH over https, the full `NetworkPathE2ETest` class passes: 12 tests, 0 failures, 1 physical-only skip.
artifact: not_applicable
artifact_evidence: The change publishes no artifact of its own.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPL-NO-LOOPBACK | VPN-1795774217912211 | Emulator API 35, `NetworkPathE2ETest#vpnServiceOwnsNoLoopbackListenerWhileTcpAndUdpFlow`, run 5. Observed: zero loopback listeners after TCP and UDP round trips; proxy logs `addr=unix` | Passed |
| REQ-VPL-PRIVATE-PATH | VPN-1795774216970611 | Emulator API 35, `NetworkPathE2ETest#vpnServiceOwnsNoLoopbackListenerWhileTcpAndUdpFlow`, run 5; `:core:service:testStandardDebugUnitTest`. Observed: one `px-` file while running, none after stop; sweep tests pass | Passed |
| REQ-VPL-TCP | VPN-1795774213020254 | `cargo test --locked -p ripdpi-tunnel-core`; Emulator API 35, `NetworkPathE2ETest#vpnServiceOwnsNoLoopbackListenerWhileTcpAndUdpFlow`, run 5 | Passed |
| REQ-VPL-UDP | VPN-1795774211939093 | `cargo test --locked -p ripdpi-proxy-runtime -p ripdpi-tunnel-core`; Emulator API 35, `NetworkPathE2ETest#vpnServiceOwnsNoLoopbackListenerWhileTcpAndUdpFlow`, run 5. Observed: the Linux-only remote-error test over the Unix socket passes in CI run 46167726044 | Passed |
| REQ-VPL-DNS-UNIX | VPN-1795862284401738 | `cargo test --locked -p ripdpi-dns-resolver -p ripdpi-tunnel-core -p ripdpi-ech-dns`. Observed: the emulator `NetworkPathE2ETest` class resolves names over the https DoH fixture | Passed |
| REQ-VPL-AUTH | VPN-1795774210951595 | `cargo test --locked -p ripdpi-proxy-runtime` (`tests/unix_listener.rs`) | Passed |
| REQ-VPL-INAPP | VPN-1795774215813180 | `LocalSocksUnixRouteInstrumentedTest` on emulators API 27 and API 35; `:app:testGithubDebugUnitTest`; `cargo test --locked -p ripdpi-diagnostics-transport`. Observed: 7 of 7 on both API levels | Passed |
| REQ-VPL-COMPAT | VPN-1795774216970611 | `:core:service:testStandardDebugUnitTest`; emulator proxy-mode tests of `NetworkPathE2ETest`. Observed: the CI `XrayProviderE2ETest` acceptance on API 35 passes in run 46167726044 | Passed |
| REQ-VPL-CONTRACT | VPN-1795774213020254 | `cargo test --locked -p ripdpi-tunnel-android -p ripdpi-tunnel-config`; `:core:engine:testStandardDebugUnitTest` | Passed |
| REQ-VPL-FAILSTART | VPN-1795774210951595 | `cargo test --locked -p ripdpi-proxy-runtime`; `:core:service:testStandardDebugUnitTest` | Passed |
