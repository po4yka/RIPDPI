---
paths:
  - "core/service/**/*.kt"
  - "core/engine/**/*.kt"
  - "native/rust/**/*.rs"
---

## VpnService.protect() invariant

`android.net.VpnService.protect(int socketFd)` prevents an app-owned outbound socket from being routed back into the TUN device owned by the active VPN. Apply this invariant to sockets created while the VPN service has registered a protection callback.

### Contract

- For a non-loopback direct-path socket created while a protection callback is registered, protect the fd before `connect()` or before outbound use. If protection fails, close the socket and propagate the failure.
- Loopback targets do not require protection.
- When no callback is registered because the VPN is stopped, tests run on a host, or diagnostics intentionally execute a RAW_PATH scan after stopping the service, protection is a no-op by design. RAW_PATH must not be changed to require a callback.
- Through-proxy/SOCKS paths intentionally traverse the configured tunnel and must not be rewritten as direct protected paths.

The repository provides two supported active-VPN mechanisms: the direct JNI callback in `ripdpi-android-vpn-protect-adapter` and the Unix-socket/SCM_RIGHTS fallback owned by `VpnProtectSocketServer`. Runtime selection lives in `ripdpi-runtime-platform/src/vpn_protect.rs`. `NetdClient.h::protectFromVpn` is not a supported NDK API and must not be introduced.

### Audit

Audit a socket in its lifecycle context rather than classifying every constructor globally:

```bash
rg -n "TcpStream::connect|UdpSocket::bind|TcpSocket::connect|Socket::new" native/rust --type rust
rg -n "has_protect_callback|protect_socket|runRawPathScan|RAW_PATH" native/rust core --type rust --type kotlin
```

A non-loopback direct socket that can run while the VPN callback is active and bypasses protection is critical. A loopback socket or deliberate callback-free RAW_PATH socket is not a finding.

### Cross-references

- `rust-jni` skill for JNI callback safety.
- `jni-bridge-verifier` agent for Kotlin/Rust bridge verification.
- `llm-rust-prompts.md` for review sentinels.
