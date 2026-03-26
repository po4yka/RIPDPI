# Turmoil Audit

Audited and updated: 2026-03-24 against the current `native/rust` tree and `turmoil` 0.7.1.

## Summary

`native/rust` now uses `turmoil` in test-only dev-dependencies for
`ripdpi-tunnel-core` and `ripdpi-dns-resolver`.

Current unprivileged network coverage is concentrated in:

- `ripdpi-runtime/tests/network_e2e.rs` for proxy round trips against `local-network-fixture`
- `local-network-fixture` self-tests for the fixture's own control, echo, DNS, TLS, SOCKS5, and fault wiring
- `ripdpi-dns-resolver/src/tests.rs` for DoH/DoT/DNSCrypt integration over real loopback sockets
- `ripdpi-tunnel-core` turmoil-backed session tests for deterministic TCP/UDP
  partition, hold, timeout, and repair scenarios
- `ripdpi-dns-resolver` turmoil-backed transport tests for deterministic
  bootstrap fallback and SOCKS5 timeout scenarios

Current tunnel coverage is still mostly async unit and integration-style tests
inside `ripdpi-tunnel-core`, not real Linux TUN end-to-end tests. The repo
still has privileged Linux TUN CI plumbing, but the workflow now routes through
target-discovery scripts and skips the stale `linux_tun_e2e` /
`linux_tun_soak` lanes when those in-tree targets are absent under
`ripdpi-tunnel-android`.

## Current E2E Test Matrix

| Scenario | Current coverage | Fixture requirement | CI lane | Flakiness level |
|----------|------------------|---------------------|---------|-----------------|
| Fixture control, echo, DNS, TLS, SOCKS5, and fault endpoints validate themselves | `native/rust/crates/local-network-fixture/src/lib.rs` | Self-hosted `FixtureStack` only | `rust-network-e2e`, plus workspace `nextest` in `static-analysis` | Low |
| Proxy SOCKS5 TCP/UDP/TLS, fragmented ClientHello, HTTP CONNECT, and domain policy round trips | `native/rust/crates/ripdpi-runtime/tests/network_e2e.rs` | `FixtureStack` TCP echo, UDP echo, TLS echo, DNS, SOCKS5 | `rust-network-e2e` | Low to medium |
| Proxy QUIC initial hostname routing and route telemetry | `native/rust/crates/ripdpi-runtime/tests/network_e2e.rs` | `FixtureStack` UDP echo | `rust-network-e2e` | Low to medium |
| Proxy delayed CONNECT reply, multi-flow UDP reuse, reply formatting | `native/rust/crates/ripdpi-runtime/tests/network_e2e.rs` | `FixtureStack`; nested proxy cases also require a second local proxy | `rust-network-e2e`; nested cases are skipped unless `RIPDPI_RUN_NESTED_PROXY_E2E=1` | Medium |
| Proxy fault observation and recovery under injected upstream failures | `native/rust/crates/ripdpi-runtime/tests/network_e2e.rs`, `native/rust/crates/ripdpi-runtime/tests/network_soak.rs` | `FixtureStack` fault controller | Regular PR CI only covers TCP reset; full recovery matrix is in scheduled/manual soak | Medium to high |
| Diagnostics scan soak over local DNS, HTTP, and TCP targets | `native/rust/crates/ripdpi-monitor/tests/soak.rs` | `FixtureStack` plus ad hoc `FatHttpServer` | `rust-native-soak` only | Medium to high |
| Encrypted DNS integration over DoH, DoT, DNSCrypt, SOCKS5, and pool reuse | `native/rust/crates/ripdpi-dns-resolver/src/tests.rs` | No `FixtureStack`; bespoke loopback listeners and SOCKS stub | Workspace `nextest` in `static-analysis` | Low to medium |
| Deterministic tunnel TCP/UDP partition, timeout, and reconnect simulation | `native/rust/crates/ripdpi-tunnel-core/src/session/tcp.rs`, `native/rust/crates/ripdpi-tunnel-core/src/session/udp.rs` | `turmoil::net` only | `rust-turmoil` | Low |
| Deterministic encrypted-DNS transport fallback and SOCKS5 timeout simulation | `native/rust/crates/ripdpi-dns-resolver/src/resolver.rs` tests | `turmoil::net` only | `rust-turmoil` | Low |
| WS tunnel bootstrap address resolution via encrypted DNS | `native/rust/crates/ripdpi-runtime/src/ws_bootstrap.rs` tests | `FixtureStack` DoH only | Workspace `nextest` in `static-analysis` | Low |
| Real Linux TUN end-to-end | No current in-tree test target found; workflow scripts now detect and skip stale targets instead of calling missing test names | Privileged `/dev/net/tun` plus interface ioctls | `linux-tun-e2e` / `linux-tun-soak` remain placeholder inventory lanes | High / currently unavailable in-tree |

## Local-Network-Fixture Usage

Direct `FixtureStack` users in product tests:

- `native/rust/crates/ripdpi-runtime/tests/network_e2e.rs`
- `native/rust/crates/ripdpi-runtime/tests/network_soak.rs`
- `native/rust/crates/ripdpi-monitor/tests/soak.rs`
- `native/rust/crates/ripdpi-runtime/src/ws_bootstrap.rs` test module

What those tests exercise today:

- SOCKS5 TCP echo
- SOCKS5 UDP echo
- SOCKS5 TLS echo, including fragmented ClientHello writes
- HTTP CONNECT round trips
- Domain allowed/denied policy
- Nested upstream proxy chaining
- Host-filtered upstream routing
- QUIC initial hostname extraction and route selection
- TCP reset observation
- Soak recovery from TCP reset, UDP drop, TLS abort, and SOCKS reject
- Diagnostics scan loops over local DNS/HTTP/TCP fixture targets
- WS tunnel bootstrap resolution through local DoH

## Fault Injection Coverage

The fixture can inject these outcomes:

- `TcpReset`
- `TcpTruncate`
- `UdpDrop`
- `UdpDelay`
- `TlsAbort`
- `DnsNxDomain`
- `DnsServFail`
- `DnsTimeout`
- `SocksRejectConnect`

Current product-level usage is still narrower:

| Failure mode | Where tested today | Notes |
|-------------|--------------------|-------|
| TCP reset | `network_e2e.rs`, `network_soak.rs` | Best-covered failure mode today |
| UDP drop | `network_soak.rs` | Not covered in regular PR E2E |
| TLS abort | `network_soak.rs` | Not covered in regular PR E2E |
| SOCKS reject connect | `network_soak.rs` | Not covered in regular PR E2E |
| TCP truncate / partial write | Fixture self-tests only | No product-level proxy or tunnel test |
| UDP delay | Not used outside fixture implementation | Good candidate for deterministic simulation |
| DNS NXDOMAIN | Fixture self-tests only | No product-level resolver or diagnostics test |
| DNS SERVFAIL | Fixture self-tests only | No product-level resolver or diagnostics test |
| DNS timeout | Implemented but currently unused | Clear gap |

Net result: the repo has fault hooks, but regular PR coverage only exercises a
small subset of them, and most of the timing-sensitive modes are still either
soak-only or completely unused.

## Tunnel E2E

What is covered today:

- `ripdpi-tunnel-core` has async tests for TCP splice, UDP associate/reply
  handling, cancellation, timeout handling, session eviction, smoltcp device
  behavior, packet classification, DNS intercept parsing, and socket framing.
- `ripdpi-tunnel-core` now also has turmoil-backed TCP tests for held connect
  paths and partition/repair reconnect, plus a turmoil-backed UDP association
  test for partition timeout followed by repair success.
- Those tests are mostly built with `tokio::io::duplex`, `tokio::net`
  listeners/sockets, local stub servers, and `turmoil::net`.

What is not covered today:

- I did not find a real in-tree Linux TUN end-to-end test under
  `native/rust/crates/ripdpi-tunnel-android`.
- I did not find `linux_tun_e2e.rs` or `linux_tun_soak.rs` test files.

Privileged requirements that still exist for real TUN coverage:

- `ripdpi-tun-driver` opens `/dev/net/tun`
- interface setup uses Linux ioctls
- MTU and address configuration require `CAP_NET_ADMIN`

Important repo state:

- `.github/workflows/ci.yml` still declares `linux-tun-e2e` and
  `linux-tun-soak` jobs
- `linux-tun-e2e` now routes through `scripts/ci/run-linux-tun-e2e.sh`
  instead of invoking a crate directly
- `scripts/ci/run-linux-tun-e2e.sh` and `scripts/ci/run-linux-tun-soak.sh`
  detect whether the corresponding privileged target exists before attempting
  to run it

That means the privileged tunnel lane should still be treated as stale or
incomplete when planning the migration.

## Turmoil Applicability

- Tunnel (tokio) - full compatibility
- Proxy runtime (blocking) - NOT directly compatible, needs an async wrapper
- Diagnostics (async) - compatibility depends on implementation

Why:

- `ripdpi-tunnel-core` is already tokio-native. It uses `tokio::net`,
  `tokio::io::unix::AsyncFd`, `tokio::spawn`, `tokio::time`, and
  `CancellationToken`. That makes it the best immediate fit for `turmoil`.
- `ripdpi-runtime` is a blocking runtime built on `std::net`, `mio`, threads,
  and wall-clock socket timeouts. `turmoil` mirrors `tokio::net`, not
  `std::net`, so it cannot be dropped into the proxy runtime as-is.
- `ripdpi-monitor` is mixed. The orchestration layer is thread-based and mostly
  blocking, but encrypted DNS and some optional QUIC/hickory paths already use
  tokio-backed components. Some diagnostics are good simulation candidates,
  others are not.

## What Real Networking Misses Today

The current real-socket approach is good at end-to-end wiring, but weak at
deterministic failure control.

Scenarios that are hard, missing, or flaky with real networking and become
straightforward with `turmoil`:

- Exact packet loss patterns: current fixture faults are one-shot or persistent,
  not "drop packet 3, deliver packet 4, then partition".
- Exact latency simulation: current tests rely on wall-clock sleeps and socket
  timeouts such as 100 ms, 200 ms, 500 ms, and 1500 ms.
- Network partition and reconnect: not modeled directly today.
- DNS resolution delays: the fixture supports `DnsTimeout`, but product tests do
  not exercise it and wall-clock delays are coarse.
- Concurrent connection limits: difficult to reproduce deterministically with
  real thread scheduling and local kernel socket behavior.
- Handover simulation (network change mid-connection): not representable with
  the current loopback fixture model.

Additional real-network pain points already visible in the tree:

- Nested proxy tests are environment-gated and not part of the default PR lane.
- Soak coverage is scheduled/manual only because it is long-running and timing-sensitive.
- One feature-gated resolver test can hit a public resolver and explicitly
  accepts offline/network errors, which is the opposite of deterministic CI.

## Test Scenarios That Turmoil Enables

- Deterministic packet loss patterns: model exact nth-packet loss for UDP
  associate flows, tunnel relay paths, and strategy retry evaluation.
- Exact latency simulation: assert timeout thresholds and recovery logic without
  `thread::sleep`-driven wall-clock timing.
- Network partition and reconnect: cut connectivity between simulated peers,
  then restore it and verify pooled connection recovery.
- DNS resolution delays: delay bootstrap IP resolution or encrypted DNS
  responses with exact virtual time.
- Concurrent connection limits: create reproducible overload and fairness tests
  for session eviction and candidate scoring.
- Handover simulation (network change mid-connection): swap reachable peers or
  route availability during an active session and assert fallback behavior.

## Implementation Plan

### Phase 1: turmoil for tunnel-level deterministic tests

Status on 2026-03-24: initial rollout implemented.

Goal: replace part of the missing or privileged Linux TUN E2E story with
unprivileged, deterministic async tunnel simulation.

Recommended scope:

- Add `turmoil` as a dev-dependency in `ripdpi-tunnel-core`.
- Start at the async seam that already exists: `session::tcp`,
  `session::udp`, `session::socks5`, and the `io_loop` helpers that talk to
  upstream sockets.
- Introduce a small test-only abstraction for outbound TCP creation so the
  same `TcpSession` logic can run against `tokio::net` in production and
  `turmoil::net` in tests. UDP now has a parallel turmoil session fixture in
  tests.
- Keep the TUN side simulated with in-memory packet ingress/egress rather than
  `/dev/net/tun`. The tunnel network stack is tokio-compatible; the real Linux
  device is the privileged part.
- Add deterministic tests for UDP timeout, duplicate replies, partition and
  reconnect, delayed SOCKS responses, DNS intercept timeout, and session
  eviction under pressure.
- Put these tests in regular PR CI because they do not need `sudo` or kernel
  TUN setup.

Expected outcome:

- Better tunnel confidence than the current stale Linux TUN lane
- Faster feedback than scheduled/manual privileged jobs
- A clear async simulation harness that future tunnel tests can reuse

### Phase 2: turmoil for DNS resolver tests

Status on 2026-03-24: initial transport-level rollout implemented.

Goal: move async encrypted-DNS behavior from wall-clock loopback sockets to a
deterministic simulated network.

Recommended scope:

- Add `turmoil`-backed tests around the async transport path in
  `ripdpi-dns-resolver`.
- Cover bootstrap fallback, deterministic SOCKS5 timeout, and other transport
  behaviors first; full `exchange()` migration is still follow-up work.
- Replace or supplement the real-network cases that currently rely on local
  `TcpListener` threads and timeouts.
- Keep `exchange_blocking()` coverage, but treat it as an API wrapper test over
  the async path rather than the main place for network-behavior assertions.

Expected outcome:

- Deterministic resolver timeout and fallback tests
- Coverage for `DnsTimeout`-style behavior that the current fixture exposes but
  product tests do not use
- Removal of external-network dependence from optional resolver tests

### Phase 3: turmoil for strategy evaluation under simulated conditions

Goal: evaluate candidate strategies under reproducible network conditions rather
than only under real loopback behavior.

Recommended scope:

- Build a simulation harness for strategy scoring and adaptive behavior, not a
  full rewrite of the blocking proxy runtime.
- Feed deterministic latency, loss, partition, and reconnect profiles into the
  async-capable components first: resolver behavior, QUIC probes, and any new
  async strategy executors.
- If proxy-runtime simulation is still desired after that, add an explicit
  async wrapper or separate async simulation adapter rather than trying to force
  `turmoil` into the current blocking `std::net` runtime.

Expected outcome:

- Reproducible candidate comparisons
- Better confidence in retry and fallback heuristics
- A path to simulate network handover and connection-cap scenarios that current
  loopback tests do not model

## Turmoil Does Not Replace

- real TUN E2E
- real Android instrumentation
- soak tests

`turmoil` should replace the non-privileged, timing-sensitive parts of the
matrix first. It should not become the only source of confidence for Linux TUN,
Android VPN integration, or long-run leak/regression detection.

## CI Integration

- Run `turmoil` tests in regular PR CI; they do not require privileged setup.
- Keep `rust-network-e2e` for real `std::net` integration against
  `local-network-fixture`.
- Keep Android instrumentation for emulator and app-process integration.
- Keep a reduced privileged Linux TUN lane only after the stale workflow/test
  target mismatch is corrected.
- Recommended first step: add a dedicated unprivileged script such as
  `scripts/ci/run-rust-turmoil-tests.sh`, then wire it into the existing PR CI
  next to `rust-network-e2e`.

## Recommended Next Actions

1. Fix the stale tunnel CI inventory first so the repo reflects reality.
2. Add `turmoil` only to `ripdpi-tunnel-core` in Phase 1, not to the whole workspace.
3. Treat the blocking proxy runtime as out of scope for direct `turmoil`
   migration until an explicit async adapter exists.
4. Use Phase 2 to close the current DNS timeout and fallback gaps.
