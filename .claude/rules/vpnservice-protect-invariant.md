## VpnService.protect() invariant — the highest-leverage Android networking rule

`android.net.VpnService.protect(int socketFd)` is the only way to prevent the kernel from routing a socket's traffic back into the TUN device that the VPN itself owns. Without it, every outbound socket the Rust core creates is captured by its own TUN route, producing an infinite packet loop with exponential traffic growth. This is the single most common LLM-class bug in Android Rust networking.

### Rule

Every `TcpStream::connect`, `UdpSocket::bind`, or `mio::net::*` construction in Rust code, whose target address is NOT `127.0.0.1` / `[::1]`, MUST be preceded by a successful call to a `protect_socket(fd)` helper that talks to Kotlin's `VpnService.protect()`. The helper must be invoked BEFORE `connect()` / `bind()` returns control to the caller. The protect call must return success; on failure the socket must be closed and the connection failed — NEVER silently proceed.

No exceptions for "internal" sockets, "test" sockets, or "control-plane only" sockets. The kernel does not distinguish; the loop fires for all of them.

### Two valid implementations

1. **UDS + SCM_RIGHTS (preferred — shadowsocks-android pattern).** Kotlin holds a Unix Domain Socket listener; Rust sends `SCM_RIGHTS` carrying the socket fd; Kotlin invokes `VpnService.protect(int)` and responds. Survives `JNIEnv` non-Send issues because no JNI call happens on the Rust hot path.

2. **Direct JNI callback to a stored `GlobalRef<VpnService>`.** Rust holds an `Arc<JavaVM>` plus a `GlobalRef` to the service, attaches the current thread, and invokes `protect(int)` directly. Simpler but requires `JavaVM::attach_current_thread()` on every protect call — measure the overhead on the hot path.

### Forbidden alternative

`NetdClient.h::protectFromVpn` is NOT part of the NDK ABI. It changes between Android releases and was removed in some AOSP forks. Never use it.

### Audit

`jni-bridge-verifier` (and any future `vpn-invariant-checker`) MUST grep for `TcpStream::connect`, `UdpSocket::bind`, `mio::net::TcpSocket::connect`, `tokio::net::TcpStream::connect` across all RIPDPI Rust crates and verify each non-loopback call site is preceded by `protect_socket(fd)`.

```bash
rg "TcpStream::connect|UdpSocket::bind|mio::net::TcpSocket::connect" native/rust/ --type rust -n
```

Any call site without a paired protect is a CRITICAL finding.

### Cross-references

- `rust-android-jni` skill — how to wire the JNI callback path.
- `jni-bridge-verifier` agent — automated audit.
- `llm-rust-prompts.md` — sentinel pattern entry for AI-generated diffs.
