## Context

See `proposal.md` for motivation. Current state, verified in source:

- `VpnRuntimeCompositionCoordinator` starts the native proxy with `listenPortOverride = 0` and a session token. The proxy binds `127.0.0.1` on an ephemeral port and reports the address through telemetry (`listenerAddress`). Kotlin turns it into `LocalProxyEndpoint` and gives host, port, and credentials to the tunnel (`Tun2SocksConfig`, JNI payload schema 3).
- `ripdpi-proxy-runtime` is synchronous. One accept thread hands each `std::net::TcpStream` to a worker pool. About 80 client-side signatures take `TcpStream`, and one external port (`WsTransport::relay` in `ripdpi-ws-transport-port`) receives the client stream. The client stream uses read, write, `peek`, `try_clone`, `shutdown`, timeouts, `set_nodelay`, `set_linger`, `as_raw_fd`, `peer_addr`, and `local_addr`. Desync and `TCP_INFO` code touch only the upstream stream.
- UDP uses the private SOCKS command `0x80` on loopback: the client declares its UDP source endpoint, the proxy binds a UDP relay socket and pins the client by IP endpoint, and each datagram carries a `RUF1` header with a nonce and an identifier. Remote ICMP errors come from the error queue of the upstream socket and go back to the client as `RUF1` error frames.
- `ripdpi-tunnel-core` runs on tokio. The TCP session code is already generic over the proxy stream (`run_with_connector`). The UDP session holds concrete `TcpStream` and `UdpSocket` values and gates the private path on a loopback IP.
- The app is outside its own VPN, so in-app clients that measure the tunnel path must enter the proxy listener. Four Kotlin clients share one helper (`LocalSocksOkHttp.kt`: `LocalSocksRoute`, `localSocksProxy`, `LocalSocksSocket`). The fifth client is the Rust `ripdpi-diagnostics-transport` (`TransportConfig::Socks5`), fed by the in-path route of the scan request.
- `std::os::unix::net::UnixStream::peek` is unstable on the pinned rustc 1.100.0.

## Goals / Non-Goals

- Goal: in VPN mode with the native proxy provider, no TCP listener exists for the tunnel-to-proxy hop; the tunnel and all five in-app path clients use a Unix socket in the private files directory.
- Goal: TCP behavior, UDP framing, remote-error feedback, per-app identity, and telemetry stay the same.
- Non-goal: the relay (default port 11980), WARP, AmneziaWG (10808), Xray inbound, NaiveProxy, pluggable-transport, and cloudflare-origin listeners. They stay on loopback. A follow-up task can move the repository-owned ones with the primitives from this change.
- Non-goal: proxy mode and diagnostics candidate listeners.
- Non-goal: removal of SOCKS authentication on the Unix listener.

## Decisions

- D1, transport: a filesystem `AF_UNIX` stream listener at `<filesDir>/px-<32 hex>`, bound by the Rust proxy with mode 0600. The path is 71 bytes, below the 108-byte `sun_path` limit. Rejected: the abstract namespace (other apps can name the socket and only SELinux stands between them); one socketpair per flow through JNI (a JNI call for each flow); a TCP listener with a peer-UID check (the listener stays visible to a port scan).
- D2, proxy client stream: a `ClientStream` enum with `Tcp(TcpStream)` and `Unix(UnixStream)` variants replaces `TcpStream` on the client side of `ripdpi-proxy-runtime` and in `WsTransport::relay`. It implements `Read` and `Write` and offers `try_clone`, `shutdown`, timeouts, `peek` (through `socket2::SockRef`), and a `peer()` that returns `ClientPeer::Ip(SocketAddr)` or `ClientPeer::LocalPrivate`. `LocalPrivate` passes the checks that today require a loopback peer. TCP-only options are skipped for the Unix variant. Transparent mode refuses a Unix client. The first step lands this as a refactor with no behavior change. Rejected: a generic parameter on 80 functions (monomorphization and wider diffs for no gain); wrapping a Unix descriptor in `TcpStream` (breaks the type's invariants).
- D3, UDP: the Unix listener serves only the private command `0x80`. The request source and the reply address are unspecified. After the `RUF1` preface the client sends one byte with `SCM_RIGHTS` that carries one end of an `AF_UNIX` `SOCK_DGRAM` socketpair. The proxy receives it with `MSG_CMSG_CLOEXEC`, requires exactly one descriptor of that domain and type, and sets it non-blocking. The `RUF1` header, nonce, identifiers, and error frames do not change. `EAGAIN` and `EMSGSIZE` drop the datagram, as today. Rejected: a `SOCK_SEQPACKET` listener (no tokio type and a second path); datagrams framed on the control stream (backpressure replaces drop semantics); a descriptor number sent in band (the proxy would adopt an arbitrary descriptor).
- D4, descriptor passing helper: one `send_fd` and one `recv_fd` (one byte and exactly one descriptor) in `ripdpi-socks5-core`, built on `nix`, with no `unsafe`. The proxy, the tunnel, and the diagnostics client use it.
- D5, tunnel: a `ProxyUpstream` enum (`Tcp(SocketAddr)`, `Unix(PathBuf)`) replaces `proxy_sockaddr`. TCP sessions use the existing connector seam. The UDP session holds the control stream and the datagram socket through small enums. The payload gains `socks5UnixPath`; schema version 4; address-and-port and path are mutually exclusive.
- D6, listen config: `sessionOverrides.listenUnixPath` replaces `listenPortOverride = 0` in VPN mode. `ListenConfig` gains an optional Unix path; when it is set the proxy binds no TCP socket. `on_listener_started` reports a listener address enum; telemetry shows `unix` without the path.
- D7, Kotlin routes: `LocalProxyEndpoint`, `LocalSocksRoute`, `RelayProbeEndpoint`, and `DiagnosticsInPathRouteLease` carry a sealed address, `Tcp(host, port)` or `Unix(path)`. `LocalSocksSocket` connects an `android.net.LocalSocket` for the Unix form. The first Kotlin step proves HTTPS through that socket on the lowest and the current API level before any consumer moves; if a platform TLS stack needs a real descriptor, the socket uses a `SocketImpl` that owns the Unix descriptor.
- D8, diagnostics: the in-path route of the scan request and `TransportConfig` gain the Unix form for TCP and UDP. The diagnostics wire version changes with it.
- D9, stale files: the VPN start removes `px-*` files in the files directory before it binds.
- D11, relay abort: the tunnel turns a reset from the proxy into a TCP RST toward the app and a clean end of stream into a FIN. A Unix stream has no reset, and a byte stream has no room for an in-band signal. A heuristic on the peer-closed state can abort a complete response, so the Unix path delivers an aborted relay as an end of stream. TLS and length-delimited protocols still detect the truncation.
- D12, proxied DNS: the encrypted DNS client dials its SOCKS proxy through `reqwest` (DoH) and concrete TCP stream types (DoT, DNSCrypt). It cannot dial a Unix socket without a resolver refactor. When the DNS plan routes encrypted DNS through the proxy (the user setting, or a relay upstream), the service keeps the token-protected loopback TCP listener for the session, and the tunnel refuses a Unix upstream with proxied encrypted DNS. Plain proxied DNS rides the tunnel flows and needs no resolver. A follow-up task can give the resolver a Unix transport.
- D10, authentication: the Unix listener keeps the session credentials. The username carries the per-app scope, and the handshake code stays shared with the TCP listener.

## Contracts and ownership

- One writer owns this worktree. Serialized shared files touched: `EngineContract.kt`, diagnostics schema-version constants, Rust `api-snapshot.txt` files (`ripdpi-config`, `ripdpi-proxy-config`, `ripdpi-runtime-api`, `ripdpi-ws-transport-port`, `ripdpi-diagnostics-contracts`, `ripdpi-diagnostics-transport`), round-trip fixtures under `core/engine/src/test/resources/fixtures/`, and `native/rust/Cargo.toml` (the `nix` `socket` feature). `Cargo.lock` changes only if that feature adds a package.
- JNI contracts: tunnel config payload (schema 4), proxy session overrides, telemetry `listenerAddress`.
- Rust review gates from `llm-rust-prompts.md`: `pr-reviewer` for every Rust step; `async-cancel-safety` for the tunnel step; `unsafe-code-auditor` if any `unsafe` appears; `jni-bridge-verifier` for the payload changes.

## Risks / Trade-offs

- TLS over a non-TCP `Socket` on old Android → prove it first on the lowest and the current API level (D7); the consumers move only after that test passes.
- A regression in the proxy data plane from the stream refactor → the refactor is its own commit with no behavior change, gated by the full `ripdpi-proxy-runtime` suite and the packet-smoke CLI scenarios.
- Unix datagram buffer limits for a 65,507-byte frame → set the send buffer when the pair is created and test the maximum frame.
- A leaked socket file after a kill → the start sweep (D9); the file holds no data.
- Wider wire-contract change (three payloads) → one writer, contract snapshot tests on both sides in the same commit as each payload.

## Migration Plan

No persisted state changes, so there is no data migration. Kotlin and Rust ship in one APK and change together; there is no mixed-version state. Rollback is a revert of the change commits. The steps land in order: stream refactor, Unix listener, UDP association, tunnel, diagnostics client, Kotlin socket proof, Kotlin composition and consumers, end-to-end test and documents. The loopback listener stays in use until the composition step switches VPN mode to the Unix path. Gates: `cargo test --locked` for each touched crate, `./gradlew :core:engine:testDebugUnitTest :core:service:testDebugUnitTest :app:testGithubDebugUnitTest`, `just ci-gates`, and the emulator run of `NetworkPathE2ETest`.
