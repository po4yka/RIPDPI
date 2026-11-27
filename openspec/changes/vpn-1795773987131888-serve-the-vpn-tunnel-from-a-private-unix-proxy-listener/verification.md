---
task_id: VPN-1795773987131888
change: vpn-1795773987131888-serve-the-vpn-tunnel-from-a-private-unix-proxy-listener
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: The change publishes no artifact of its own.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPL-NO-LOOPBACK | VPN-1795774217912211 | Emulator run of `NetworkPathE2ETest` | Pending |
| REQ-VPL-PRIVATE-PATH | VPN-1795774216970611 | `:core:service:testDebugUnitTest` and the proxy listener tests | Pending |
| REQ-VPL-TCP | VPN-1795774213020254 | `cargo test -p ripdpi-tunnel-core --locked` | Pending |
| REQ-VPL-UDP | VPN-1795774211939093 | `cargo test -p ripdpi-proxy-runtime --locked` and `cargo test -p ripdpi-tunnel-core --locked` | Pending |
| REQ-VPL-AUTH | VPN-1795774210951595 | `cargo test -p ripdpi-proxy-runtime --locked` | Pending |
| REQ-VPL-INAPP | VPN-1795774215813180 | Instrumented HTTPS test on the lowest and the current API level; `:app:testGithubDebugUnitTest` | Pending |
| REQ-VPL-COMPAT | VPN-1795774216970611 | Proxy-mode and Xray-provider tests in `:core:service` and the emulator run | Pending |
| REQ-VPL-CONTRACT | VPN-1795774213020254 | Kotlin and Rust contract snapshot tests | Pending |
| REQ-VPL-FAILSTART | VPN-1795774210951595 | `cargo test -p ripdpi-proxy-runtime --locked` | Pending |
