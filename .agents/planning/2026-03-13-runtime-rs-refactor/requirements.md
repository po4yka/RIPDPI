# Runtime Refactor Requirements

## Goal

Refactor `native/rust/crates/ripdpi-runtime/src/runtime.rs` into cooperating internal modules while preserving:

- current runtime behavior
- protocol semantics and failure handling
- performance-sensitive hot paths
- ownership and locking clarity
- the existing public runtime entry points

This is a planning-only task. No production behavior changes are allowed as part of the refactor itself.

## Verified Current Responsibilities

The current `runtime.rs` file is 2870 lines and owns these responsibilities today:

### 1. Listener bootstrap and lifecycle

Verified in:

- `run_proxy`, `create_listener`, `run_proxy_with_listener`, `run_proxy_with_embedded_control` at `runtime.rs:75`
- `run_proxy_with_listener_internal` at `runtime.rs:96`
- `build_listener` at `runtime.rs:227`
- `RuntimeCleanup` at `runtime.rs:189`
- `ClientSlotGuard` at `runtime.rs:203`

Behavior currently owned here:

- detects default TTL once before serving if `config.default_ttl == 0`
- loads `RuntimeCache` and flushes autolearn telemetry before accepting clients
- configures the listener as nonblocking and drives a `mio::Poll` accept loop
- enforces `max_open` using `ClientSlotGuard`
- spawns one OS thread per accepted TCP client
- polls for shutdown every 250ms
- emits listener/client telemetry lifecycle events
- dumps stdout-backed cache groups on runtime drop

### 2. Protocol dispatch and handshake parsing

Verified in:

- `handle_client` at `runtime.rs:242`
- `handle_transparent` at `runtime.rs:264`
- `handle_socks4` at `runtime.rs:285`
- `handle_socks5` at `runtime.rs:318`
- `handle_http_connect` at `runtime.rs:375`
- `handle_shadowsocks` at `runtime.rs:408`
- `handle_socks5_udp_associate` at `runtime.rs:416`
- request parsing helpers between `runtime.rs:448` and `runtime.rs:687`

Behavior currently owned here:

- assigns handshake read/write deadlines before protocol dispatch
- selects transparent / HTTP CONNECT / SOCKS4 / SOCKS5 / Shadowsocks entry flow
- performs protocol-specific reply encoding and error mapping
- supports delayed connect (`delay_conn`) by replying before first payload only when required
- resolves domain targets according to `resolve` / `ipv6` config
- keeps protocol parsing size limits and EOF/error semantics local to runtime

### 3. Route selection and connection orchestration

Verified in:

- `select_route` / `select_route_for_transport` at `runtime.rs:688`
- `connect_target` / `connect_target_with_route` at `runtime.rs:711`
- failure/route advancement helpers between `runtime.rs:748` and `runtime.rs:991`
- direct/upstream connect helpers between `runtime.rs:1270` and `runtime.rs:1688`

Behavior currently owned here:

- wraps `runtime_policy.rs` selection logic with cache locking and runtime telemetry
- connects directly or through upstream SOCKS according to group config
- classifies connect and first-response failures
- advances routes only for supported trigger classes
- persists route success/autolearn updates back into `RuntimeCache`
- preserves `"initial"` / `"advanced"` telemetry phase names

### 4. Adaptive tuning and retry pacing glue

Verified in:

- adaptive helpers between `runtime.rs:1004` and `runtime.rs:1116`
- retry signature / pacing helpers between `runtime.rs:1128` and `runtime.rs:1258`

Behavior currently owned here:

- resolves adaptive fake TTL, adaptive TCP hints, and adaptive UDP hints
- records adaptive success/failure feedback on TCP and UDP flows
- builds retry signatures from network scope, target, transport lane, fake TTL, and adaptive hints
- computes route-selection penalties from retry pacing state
- sleeps before reconnect when retry pacing requires it
- emits retry pacing telemetry on enforced delay or candidate diversification

### 5. UDP associate flow handling

Verified in:

- `build_udp_relay_socket` at `runtime.rs:1368`
- `udp_associate_loop` at `runtime.rs:1386`
- `expire_udp_flows` at `runtime.rs:1500`
- UDP packet codec helpers between `runtime.rs:1567` and `runtime.rs:1657`

Behavior currently owned here:

- binds a per-associate UDP relay socket and worker thread
- pins a single client address per SOCKS5 UDP associate session
- tracks per-flow activation state keyed by `(client_addr, target_addr)`
- caches UDP host routing only for eligible host sources and QUIC modes
- converts UDP inactivity into retry/adaptive failure and route advancement
- forwards responses back to the SOCKS5 UDP client with the correct encapsulation

### 6. Relay loops and first-response inspection

Verified in:

- `relay` at `runtime.rs:1696`
- `relay_streams` at `runtime.rs:1838`
- first-response helpers between `runtime.rs:1889` and `runtime.rs:2178`
- half-copy loops between `runtime.rs:2198` and `runtime.rs:2264`

Behavior currently owned here:

- optionally captures the first outbound request before steady-state relay
- sends the first request through desync logic
- classifies first response for redirect / TLS alert / blockpage / DNS tampering / silent drop
- learns observed server TTL when available
- reconnects and retries on eligible failures
- spawns two relay threads per TCP connection for inbound/outbound halves
- keeps session tracking synchronized through `Arc<Mutex<SessionState>>`

### 7. Desync execution

Verified in:

- `send_with_group` at `runtime.rs:2265`
- TCP/UDP desync helpers between `runtime.rs:2305` and `runtime.rs:2646`

Behavior currently owned here:

- decides whether TCP desync should activate for a given `ActivationContext`
- builds TCP and UDP plans using `ciadpi_desync`
- executes special TCP fake/fake-split/fake-disorder flows with platform helpers
- preserves default TTL restore behavior
- performs urgent/OOB writes, MD5SIG, and wait stages
- falls back to raw `write_all` on plan construction failure

## Existing Characterization Baseline

Confirmed on 2026-03-13:

- `cargo test -p ripdpi-runtime runtime::tests --manifest-path native/rust/Cargo.toml` passes when run outside the sandbox because the test helpers bind local sockets
- `cargo test -p ripdpi-runtime --test network_e2e socks5_tcp_udp_tls_domain_chain_and_filtering_are_covered_end_to_end --manifest-path native/rust/Cargo.toml` passes

Additional relevant baseline already present in adjacent modules:

- `runtime_policy.rs` already has extensive pure logic tests
- `adaptive_tuning.rs` already has focused adaptive planner tests
- `adaptive_fake_ttl.rs` already has focused resolver tests
- `retry_stealth.rs` already has focused pacing tests

The refactor plan must build on this coverage instead of replacing it.

## Hard Requirements

1. Preserve the public runtime API surface.

- `run_proxy`
- `create_listener`
- `run_proxy_with_listener`
- `run_proxy_with_embedded_control`

2. Keep `runtime.rs` thin and readable after the refactor.

3. Use plain modules and free functions or small internal structs.

- no trait-heavy service layer
- no async rewrite
- no new synchronization primitives unless a proven race requires them

4. Preserve current ownership and lock scope behavior.

- do not widen `Mutex` hold times
- do not introduce nested locking that is not present today
- do not clone large payloads more often than today

5. Preserve transport semantics.

- SOCKS4/SOCKS5/HTTP CONNECT/Shadowsocks behavior must remain byte-compatible
- retry pacing decisions must remain signature-based
- UDP flow expiry must still feed retry/adaptive failure handling
- first-response classification must still gate route advancement

6. Preserve performance-sensitive execution paths.

- client accept loop
- TCP outbound `send_with_group`
- first-response read loop
- steady-state relay copy loops
- UDP receive/forward loop

7. Keep module boundaries practical.

- extraction should follow existing responsibility seams
- existing pure helper modules (`runtime_policy`, `adaptive_tuning`, `adaptive_fake_ttl`, `retry_stealth`) remain authoritative for their internal algorithms

## Risk Areas To Protect

| Area | Risk | Protection rule |
| --- | --- | --- |
| Listener accept loop | Extra indirection or locking can reduce accept throughput or change shutdown cadence | Keep `mio::Poll`, token handling, and `ClientSlotGuard` logic structurally unchanged during extraction |
| Retry/adaptive glue | Small call-order changes can silently alter route selection or pacing | Preserve helper call order and telemetry emission order exactly |
| UDP associate | Worker-thread lifetime and `udp_client_addr` pinning are easy to break | Extract as a closed unit with dedicated characterization before movement |
| Relay loops | Hot path; easy to add extra allocations, lock scope, or ordering bugs | Keep buffer sizes, thread split, and shutdown sequence unchanged |
| First-response logic | Timeout handling is protocol-sensitive and coupled to TLS partial tracking | Move `read_first_response` and `TlsRecordTracker` together, not separately |
| Desync execution | Platform-specific syscalls and TTL manipulation are brittle | Extract only after characterization and keep direct platform calls, not abstractions |
| Cache/autolearn flushing | Missing a flush point changes long-term routing behavior | Preserve each current flush site after route select/success/advance/cleanup |

## Required Test Strategy

### Characterization tests

Add or strengthen tests around observable runtime behavior before moving code:

- public entry points and listener lifecycle
- protocol handshake byte replies and error codes
- route selection telemetry and route advancement telemetry
- first-response failure classification and reconnect behavior
- UDP associate round-trip, host-aware QUIC routing, and expiry behavior

### Unit tests

Focus new unit coverage on logic extracted out of `runtime.rs`:

- runtime-specific routing glue and trigger handling
- adaptive glue call sequencing
- retry signature construction and pacing selection inputs
- protocol decision helpers
- TLS partial-record tracking and timeout choice

### Integration tests

Keep and expand critical runtime flows:

- SOCKS5 TCP round-trip
- SOCKS5 UDP associate round-trip
- HTTP CONNECT round-trip
- chained upstream proxy route
- first-response fault and recovery scenarios

### Property-style tests

Use property tests only where the logic is pure and invariant-heavy:

- SOCKS5 UDP packet encode/decode round-trip
- retry lane/signature normalization invariants
- TLS record tracking under arbitrary chunk splits
- routing penalty ordering invariants that do not require sockets

## Non-Goals

- changing proxy features
- changing `RuntimeConfig` structure
- replacing thread-per-client with async I/O
- rewriting `runtime_policy.rs`, `adaptive_tuning.rs`, `adaptive_fake_ttl.rs`, or `retry_stealth.rs`
- broad crate reorganization beyond what is needed to split `runtime.rs`
